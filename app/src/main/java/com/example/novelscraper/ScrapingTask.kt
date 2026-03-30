package com.example.novelscraper

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.random.Random

class ScrapingTask(
    private val context: Context,
    private val startUrl: String,
    val config: ScraperConfig,
    private val useImages: Boolean,
    private val isDesktop: Boolean,
    private val initialFolderName: String = "(取得中...)",
    private val taskListener: TaskListener
) {
    interface TaskListener {
        fun onStatusUpdate(task: ScrapingTask, status: String)
        fun onTaskFinished(task: ScrapingTask)
        fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String)
        fun onUpdateHistory(folderName: String, title: String, chapter: String, url: String, config: ScraperConfig)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val webView = WebView(context.applicationContext)

    var currentUrl = startUrl
    var lastSuccessUrl = ""
    var retryCount = 0
    var status = "準備中..."
    var folderName = initialFolderName
    var isRunning = true
    private var state = TaskState.INITIAL_CHECK
    private var manualChapterCounter: Int? = null

    private enum class TaskState { INITIAL_CHECK, FETCHING_FOLDER, RETURNING, SCRAPING }

    init {
        if (config.chapter.startsWith("@") && config.chapter.length > 1) {
            manualChapterCounter = config.chapter.substring(1).toIntOrNull()
        }
        WebViewHelper.applyStandardSettings(webView, !useImages, isDesktop)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isRunning) return
                val loadedUrl = url ?: return
                currentUrl = loadedUrl
                verifyTurnstile(view)
                if (isCloudflareTitle(view?.title ?: "")) {
                    triggerCFWait()
                    return
                }
                if (state == TaskState.SCRAPING) performHumanLikeScroll(view)
                scope.launch {
                    val delayMs = if (state == TaskState.SCRAPING) calculateDelay(config.delay) else 2000L
                    delay(delayMs)
                    if (!isRunning) return@launch
                    when (state) {
                        TaskState.INITIAL_CHECK -> checkFolderLink()
                        TaskState.FETCHING_FOLDER -> fetchFolderName()
                        TaskState.RETURNING -> {
                            state = TaskState.SCRAPING
                            processPage()
                        }
                        TaskState.SCRAPING -> processPage()
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (!isRunning || request?.isForMainFrame == false) return
                handleTaskRetry("通信エラー: ${error?.errorCode}")
            }
        }
    }

    private fun verifyTurnstile(view: WebView?) {
        view?.evaluateJavascript(
            "(function(){ if(!window.cfStarted){ window.cfStarted=true; setInterval(function(){ var btn=document.querySelector('input[type=\"checkbox\"]'); if(btn && !btn.checked) btn.click(); }, 1500); } })();",
            null
        )
    }

    private fun performHumanLikeScroll(view: WebView?) {
        view?.evaluateJavascript(
            "(function(){ var h=document.body.scrollHeight; var s=0; function sc(){ if(s>=h) return; s+=Math.random()*50+20; window.scrollTo(0,s); setTimeout(sc, Math.random()*100+50); } sc(); })();",
            null
        )
    }

    private fun isCloudflareTitle(title: String): Boolean =
        title.contains("Just a moment") || title.contains("Cloudflare") || title.contains("Verify")

    private fun triggerCFWait() {
        updateStatus("認証待ち...")
        scope.launch {
            delay(30000)
            if (isRunning) webView.reload()
        }
    }

    fun start() {
        state = TaskState.INITIAL_CHECK
        webView.loadUrl(startUrl)
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        webView.stopLoading()
        webView.destroy()
    }

    private fun handleTaskRetry(reason: String) {
        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            updateStatus("リトライ($retryCount): $reason")
            scope.launch {
                delay(RETRY_DELAY_MS)
                if (isRunning) webView.loadUrl(currentUrl)
            }
        } else {
            updateStatus("エラー停止: $reason")
            isRunning = false
            taskListener.onTaskFinished(this)
        }
    }

    private fun checkFolderLink() {
        if (config.folderLink.isEmpty()) {
            fetchFolderNameInPlace()
            return
        }
        val js = "(function(){ var el=document.querySelector('${config.folderLink.replace("'", "\\'")}'); return el?el.href:''; })();"
        webView.evaluateJavascript(js) { res ->
            val link = res?.replace("\"", "") ?: ""
            if (link.isNotEmpty() && link != "null") {
                state = TaskState.FETCHING_FOLDER
                webView.loadUrl(link)
            } else {
                fetchFolderNameInPlace()
            }
        }
    }

    private fun fetchFolderName() {
        val js = "(function(){ var el=document.querySelector('${config.folder.replace("'", "\\'")}'); return el?el.innerText.trim():''; })();"
        webView.evaluateJavascript(js) { res ->
            val name = res?.replace("\"", "") ?: ""
            if (name.isNotEmpty()) {
                folderName = name
                updateStatus("作品名取得: $folderName")
            }
            state = TaskState.RETURNING
            webView.loadUrl(startUrl)
        }
    }

    private fun fetchFolderNameInPlace() {
        if (config.folder.startsWith("@")) {
            folderName = config.folder.substring(1)
        }
        state = TaskState.SCRAPING
        processPage()
    }

    private fun processPage() {
        if (!isRunning || currentUrl == lastSuccessUrl) return
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, useImages)
        webView.evaluateJavascript(jsCode) { jsonResult ->
            if (!isRunning) return@evaluateJavascript
            try {
                val rawResult = Json.decodeFromString<String>(jsonResult ?: "null")
                if (rawResult == "CF_DETECTED") {
                    triggerCFWait()
                    return@evaluateJavascript
                }
                if (rawResult.startsWith("JS_ERROR")) {
                    handleTaskRetry(rawResult)
                    return@evaluateJavascript
                }

                val data = Json.decodeFromString<ScrapingResult>(rawResult)
                if (data.folderName.isNotEmpty()) this.folderName = data.folderName

                val title = data.title.ifEmpty { "無題" }
                val chapNum = ChapterNumberExtractor.extract(data.chapter, config.chapter, currentUrl, manualChapterCounter)
                if (manualChapterCounter != null) manualChapterCounter = manualChapterCounter!! + 1

                if (data.content.length < MIN_CONTENT_LENGTH) {
                    handleTaskRetry("本文過少")
                    return@evaluateJavascript
                }

                taskListener.onSaveResult(this.folderName, title, data.content, chapNum)
                taskListener.onUpdateHistory(this.folderName, title, chapNum, currentUrl, config)

                lastSuccessUrl = currentUrl
                retryCount = 0
                updateStatus("保存: $chapNum ${title.take(10)}...")

                val shouldStop = isEndDetected(data.nextUrl, title)
                if (data.nextUrl.isNotEmpty() && data.nextUrl != "null" && !shouldStop) {
                    val delayMs = calculateDelay(config.delay)
                    scope.launch {
                        delay(delayMs)
                        if (isRunning) webView.loadUrl(data.nextUrl)
                    }
                } else {
                    updateStatus(if (shouldStop) "終了検知" else "完了")
                    isRunning = false
                    taskListener.onTaskFinished(this)
                }
            } catch (e: Exception) {
                handleTaskRetry("エラー: ${e.message}")
            }
        }
    }

    private fun isEndDetected(nextUrl: String, title: String): Boolean {
        if (config.endCheck.isEmpty()) return false
        val regex = try { Regex(config.endCheck) } catch (e: Exception) { return false }
        return regex.containsMatchIn(nextUrl) || regex.containsMatchIn(title) ||
                nextUrl.contains("/null") || nextUrl.endsWith("null")
    }

    private fun calculateDelay(delayStr: String): Long {
        return try {
            if (delayStr.contains("-")) {
                val parts = delayStr.split("-")
                val min = parts[0].trim().toLongOrNull() ?: DEFAULT_DELAY_SECONDS
                val max = parts[1].trim().toLongOrNull() ?: min
                Random.nextLong(min, max + 1) * 1000
            } else {
                (delayStr.toLongOrNull() ?: DEFAULT_DELAY_SECONDS) * 1000
            }
        } catch (e: Exception) {
            DEFAULT_DELAY_SECONDS * 1000
        }
    }

    private fun updateStatus(newStatus: String) {
        status = newStatus
        taskListener.onStatusUpdate(this, status)
    }

    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 60_000L
        private const val DEFAULT_DELAY_SECONDS = 2L
        private const val MIN_CONTENT_LENGTH = 20
    }
}