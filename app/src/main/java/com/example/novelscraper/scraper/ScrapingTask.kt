package com.example.novelscraper.scraper

import com.example.novelscraper.WebViewHelper

import android.content.Context
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*

/**
 * WebView操作のオーケストレーター。
 * 状態遷移ロジックはScrapingStateMachineに委譲し、
 * このクラスはWebView操作とCoroutine制御のみを担当する。
 */
class ScrapingTask(
    context: Context,
    val startUrl: String,
    val config: ScraperConfig,
    private val useImages: Boolean,
    private val isDesktop: Boolean,
    initialFolderName: String = "(取得中...)",
    private val taskListener: TaskListener
) {
    interface TaskListener {
        fun onStatusUpdate(task: ScrapingTask, status: String)
        fun onTaskFinished(task: ScrapingTask)
        fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String)
        fun onUpdateHistory(folderName: String, title: String, chapter: String, url: String, nextUrl: String, config: ScraperConfig)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val webView = WebView(context.applicationContext)
    private val stateMachine = ScrapingStateMachine(config, startUrl, initialFolderName)

    var isRunning = true
    private var navigationJob: Job? = null
    private var isPageError = false

    // StateMachineの状態を外部公開（UI表示用）
    val currentUrl: String get() = stateMachine.currentUrl
    val lastSuccessUrl: String get() = stateMachine.lastSuccessUrl
    val retryCount: Int get() = stateMachine.retryCount
    val folderName: String get() = stateMachine.folderName
    var status = "準備中..."
        private set

    init {
        WebViewHelper.applyStandardSettings(webView, !useImages, isDesktop)
        // PCモード時は横長デスクトップ解像度(1920x1080)、通常時は縦長(1080x1920)を仮想配置
        if (isDesktop) {
            WebViewHelper.applyVirtualSize(webView, width = 1920, height = 1080)
        } else {
            WebViewHelper.applyVirtualSize(webView, width = 1080, height = 1920)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isRunning) return
                val loadedUrl = url ?: return
                if (loadedUrl.startsWith("javascript:") || loadedUrl.startsWith("data:")) return
                if (isPageError) return // エラー時はonPageFinishedを処理しない

                // CF対策(生体タップ + スクロール模倣) + PCモード時のViewport除去を単一IPCで集約実行
                val shouldScroll = stateMachine.state == ScrapingStateMachine.State.SCRAPING
                view?.evaluateJavascript(CloudflareDetector.buildPageLoadInitJs(shouldScroll, isDesktop), null)

                navigationJob?.cancel()
                navigationJob = scope.launch {
                    val delayMs = if (stateMachine.state == ScrapingStateMachine.State.SCRAPING) {
                        stateMachine.calculateDelay()
                    } else {
                        INITIAL_PAGE_DELAY_MS
                    }
                    delay(delayMs)
                    if (!isRunning) return@launch

                    val actions = stateMachine.onPageLoaded(loadedUrl, view?.title ?: "")
                    executeActions(actions)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (!isRunning || request?.isForMainFrame == false) return
                isPageError = true
                navigationJob?.cancel()
                val actions = stateMachine.onNetworkError(error?.errorCode ?: -1)
                executeActions(actions)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: false
                val reason = if (didCrash) "レンダラークラッシュ (C++エラー)" else "メモリ不足によるOS強制終了 (OOM)"
                Log.e(TAG, "WebView onRenderProcessGone 検知: $reason")
                updateStatus("エラー停止: $reason")
                stop()
                taskListener.onTaskFinished(this@ScrapingTask)
                return true // ホストアプリの道連れクラッシュを100%阻止
            }
        }
    }

    fun start() {
        isPageError = false
        navigationJob?.cancel()
        webView.loadUrl(startUrl)
    }

    fun stop() {
        isRunning = false
        navigationJob?.cancel()
        scope.cancel()
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {}
    }

    /** StateMachineから返されたActionリストをWebView操作に変換 */
    private fun executeActions(actions: List<ScrapingStateMachine.Action>) {
        if (!isRunning) return
        for (action in actions) {
            if (!isRunning) return
            when (action) {
                is ScrapingStateMachine.Action.LoadUrl -> {
                    isPageError = false
                    navigationJob?.cancel()
                    // 技術的根拠1行：破棄競合でのloadUrl例外をここで握り潰さず有限リトライに寄せ、停滞死骸化を防ぐ。
                    try {
                        webView.loadUrl(action.url)
                    } catch (e: Exception) {
                        Log.w(TAG, "loadUrl failed, routing to bounded retry", e)
                        executeActions(stateMachine.onViewError())
                    }
                }
                is ScrapingStateMachine.Action.EvaluateJs -> {
                    try {
                        webView.evaluateJavascript(action.js) { result ->
                            if (!isRunning) return@evaluateJavascript
                            val followUpActions = stateMachine.onJsResult(action.purpose, result ?: "null")
                            executeActions(followUpActions)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "evaluateJavascript failed, routing to bounded retry", e)
                        executeActions(stateMachine.onViewError())
                    }
                }
                is ScrapingStateMachine.Action.SaveAndContinue -> {
                    taskListener.onSaveResult(action.folderName, action.title, action.content, action.chapterNum)
                }
                is ScrapingStateMachine.Action.UpdateHistory -> {
                    taskListener.onUpdateHistory(action.folderName, action.title, action.chapter, action.url, action.nextUrl, config)
                }
                is ScrapingStateMachine.Action.UpdateStatus -> {
                    updateStatus(action.message)
                }
                is ScrapingStateMachine.Action.WaitAndLoad -> {
                    // 1話ごとの予防的メモリ自動パージ：
                    // 戻る/進む履歴（DOMスナップショット）とRAM一時キャッシュを消去し、
                    // 長時間スクレイピング時のChromiumレンダラープロセスのメモリ肥大化（OOM）を根本から予防
                    try {
                        webView.clearHistory()
                        webView.clearCache(false)
                    } catch (_: Exception) {}

                    navigationJob?.cancel()
                    navigationJob = scope.launch {
                        delay(action.delayMs)
                        if (isRunning) {
                            isPageError = false
                            webView.loadUrl(action.url)
                        }
                    }
                }
                is ScrapingStateMachine.Action.WaitAndScrapeAgain -> {
                    // SPA空本文対策: 同一ページで指定ディレイ待機後、再スクレイピングを実行
                    navigationJob?.cancel()
                    navigationJob = scope.launch {
                        delay(action.delayMs)
                        if (isRunning) {
                            val scrapeAction = stateMachine.buildScrapePageAction()
                            executeActions(listOf(scrapeAction))
                        }
                    }
                }
                is ScrapingStateMachine.Action.WaitForCF -> {
                    navigationJob?.cancel()
                    navigationJob = scope.launch {
                        delay(action.delayMs)
                        if (isRunning) {
                            isPageError = false
                            webView.reload()
                        }
                    }
                }
                is ScrapingStateMachine.Action.Finish -> {
                    updateStatus(action.reason)
                    taskListener.onTaskFinished(this)
                }
            }
        }
    }

    private fun updateStatus(newStatus: String) {
        status = newStatus
        taskListener.onStatusUpdate(this, newStatus)
    }

    companion object {
        private const val TAG = "ScrapingTask"
        private const val INITIAL_PAGE_DELAY_MS = 2000L
    }
}