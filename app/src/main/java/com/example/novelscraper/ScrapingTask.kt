package com.example.novelscraper

import android.content.Context
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
    private val startUrl: String,
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isRunning) return
                val loadedUrl = url ?: return
                if (loadedUrl.startsWith("javascript:") || loadedUrl.startsWith("data:")) return
                if (isPageError) return // エラー時はonPageFinishedを処理しない

                // CF対策(Turnstile自動クリック)とスクロール模倣を単一のevaluateJavascript呼び出しに集約（IPC通信回数の半減）
                val shouldScroll = stateMachine.state == ScrapingStateMachine.State.SCRAPING
                view?.evaluateJavascript(CloudflareDetector.buildPageLoadInitJs(shouldScroll), null)

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
        webView.stopLoading()
        webView.destroy()
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
                    webView.loadUrl(action.url)
                }
                is ScrapingStateMachine.Action.EvaluateJs -> {
                    webView.evaluateJavascript(action.js) { result ->
                        if (!isRunning) return@evaluateJavascript
                        val followUpActions = stateMachine.onJsResult(action.purpose, result ?: "null")
                        executeActions(followUpActions)
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
                    navigationJob?.cancel()
                    navigationJob = scope.launch {
                        delay(action.delayMs)
                        if (isRunning) {
                            isPageError = false
                            webView.loadUrl(action.url)
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
                is ScrapingStateMachine.Action.Retry -> {
                    // リトライはStateMachineがWaitAndLoadに変換するため通常到達しない
                }
                is ScrapingStateMachine.Action.Finish -> {
                    updateStatus(action.reason)
                    isRunning = false
                    taskListener.onTaskFinished(this)
                }
                is ScrapingStateMachine.Action.Error -> {
                    updateStatus(action.message)
                }
            }
        }
    }

    private fun updateStatus(newStatus: String) {
        status = newStatus
        taskListener.onStatusUpdate(this, status)
    }

    companion object {
        private const val INITIAL_PAGE_DELAY_MS = 2000L
    }
}