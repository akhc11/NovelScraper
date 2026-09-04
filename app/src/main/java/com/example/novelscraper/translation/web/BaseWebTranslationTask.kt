package com.example.novelscraper.translation.web

import com.example.novelscraper.WebViewHelper

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * 独立したバックグラウンド WebView インスタンスで動作する Web 翻訳共通タスク。
 * Strategy パターンにより Google / DeepL / Papago 翻訳の固有 DOM 差異を吸収する。
 */
class BaseWebTranslationTask(
    private val context: Context,
    private val folderUri: Uri,
    private val strategy: WebTranslationStrategy,
    private val sourceLang: String = "auto",
    private val targetLang: String = "ja",
    private val chunkDelay: String = "",
    private val fileDelay: String = "",
    private val listener: TranslationListener
) {

    interface TranslationListener {
        fun onProgress(
            completedFiles: Int,
            totalFiles: Int,
            currentFileName: String,
            currentChunk: Int,
            totalChunks: Int,
            statusText: String
        )
        fun onTaskFinished(success: Boolean, message: String)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileStore = TranslationFileStore(context, strategy.outputFolderName)
    private val webView = WebView(context.applicationContext)

    // Activityの破棄・バックグラウンド移行と連動してキャンセルされない独立したScope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    @Volatile
    private var isRunning = false

    init {
        WebViewHelper.applyStandardSettings(webView, blockImages = false, isDesktop = strategy.isDesktop)
        WebViewHelper.applyVirtualSize(webView) // ヘッドレス（0x0）判定を解除

        // レンダラープロセス強制終了(OOM等)の安全ハンドラを常駐
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: false
                val reason = if (didCrash) "レンダラークラッシュ (C++エラー)" else "メモリ不足によるOS強制終了 (OOM)"
                Log.e(TAG, "WebView onRenderProcessGone 検知: $reason")
                listener.onTaskFinished(false, "翻訳エラー停止: $reason")
                stop()
                return true // ホストアプリの道連れクラッシュを100%阻止
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        job = scope.launch {
            try {
                // バックグラウンド時にもJavaScriptタイマーが停止しないよう明示的に再開
                mainHandler.post { webView.resumeTimers() }

                listener.onProgress(0, 0, "", 0, 0, "対象ファイルを確認中...")

                val pendingFiles = fileStore.getPendingTextFiles(folderUri)
                if (pendingFiles.isEmpty()) {
                    listener.onProgress(0, 0, "", 0, 0, "翻訳対象のファイルがありません")
                    listener.onTaskFinished(true, "翻訳対象の未完了ファイルがありません")
                    stop()
                    return@launch
                }

                val totalFiles = pendingFiles.size
                listener.onProgress(0, totalFiles, "", 0, 0, "${strategy.engineName}翻訳を初期化中...")

                val targetUrl = strategy.buildTargetUrl(sourceLang, targetLang)

                // 独立した裏WebViewで翻訳ページを初期化
                if (!strategy.isTargetPageUrl(webView.url)) {
                    val pageLoaded = CompletableDeferred<Boolean>()

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) {
                            if (url != null && !url.startsWith("javascript:") && !pageLoaded.isCompleted) {
                                pageLoaded.complete(true)
                            }
                        }

                        override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            Log.e(TAG, "初期ロード中に onRenderProcessGone を検知")
                            if (!pageLoaded.isCompleted) pageLoaded.complete(false)
                            return true
                        }
                    }

                    mainHandler.post {
                        webView.loadUrl(targetUrl)
                    }

                    withTimeout(strategy.pageLoadTimeoutMs) {
                        pageLoaded.await()
                    }
                }

                if (!isRunning) return@launch

                // PC版ビューポートの最適化スクリプト注入
                if (strategy.isDesktop) {
                    evalJs(webView, WebViewHelper.buildDesktopViewportJs(true))
                }

                // DOMが操作可能になるまで待機
                listener.onProgress(0, totalFiles, "", 0, 0, "${strategy.engineName}の入力枠を待機中...")
                waitForDomReady(webView)

                var completedCount = 0
                var consecutiveFailureCount = 0
                val maxConsecutiveFailures = 2 // 連続2ファイル失敗で全体停止（サーキットブレーカー）
                val failedFileNames = mutableListOf<String>()

                for ((index, fileInfo) in pendingFiles.withIndex()) {
                    if (!isRunning) break

                    val currentFileNum = index + 1
                    listener.onProgress(
                        completedCount, totalFiles, fileInfo.name, 0, 0,
                        "ファイル読込中 ($currentFileNum/$totalFiles)"
                    )

                    val readResult = fileStore.readTextFile(fileInfo.uri)
                    if (readResult.isFailure) {
                        Log.e(TAG, "Failed to read file: ${fileInfo.name}", readResult.exceptionOrNull())
                        consecutiveFailureCount++
                        failedFileNames.add(fileInfo.name)
                        if (consecutiveFailureCount >= maxConsecutiveFailures) {
                            val stopMsg = "${strategy.engineName}翻訳を停止しました: ファイルの読み込みに連続して失敗しました"
                            listener.onTaskFinished(false, stopMsg)
                            stop()
                            return@launch
                        }
                        continue
                    }

                    val originalContent = readResult.getOrDefault("")
                    if (originalContent.isEmpty()) {
                        fileStore.saveTranslatedFile(folderUri, fileInfo.name, "")
                        completedCount++
                        consecutiveFailureCount = 0 // 成功したのでリセット
                        listener.onProgress(
                            completedCount, totalFiles, fileInfo.name, 1, 1,
                            "空ファイルを保存完了 ($currentFileNum/$totalFiles)"
                        )
                        continue
                    }

                    val chunks = TextChunker.splitIntoChunks(originalContent, strategy.maxChunkSize)
                    val totalChunks = chunks.size
                    val translatedChunks = mutableListOf<String>()
                    var lastChunkResultText = ""
                    var fileSuccess = true

                    Log.d(TAG, "Starting file ${fileInfo.name}: totalLength=${originalContent.length}, totalChunks=$totalChunks")

                    for ((chunkIndex, chunkText) in chunks.withIndex()) {
                        if (!isRunning) { fileSuccess = false; break }

                        val currentChunkNum = chunkIndex + 1
                        listener.onProgress(
                            completedCount, totalFiles, fileInfo.name, currentChunkNum, totalChunks,
                            "${strategy.engineName}翻訳中 ($currentFileNum/$totalFiles, チャンク $currentChunkNum/$totalChunks)"
                        )

                        // チャンク翻訳（通信瞬断・遅延に耐えるシンプル3回リトライ）
                        var chunkResult: String? = null
                        val maxRetries = 3

                        for (retry in 1..maxRetries) {
                            if (!isRunning) break

                            chunkResult = translateChunk(webView, chunkText, lastChunkResultText)
                            if (!chunkResult.isNullOrBlank()) {
                                break // 成功！
                            }

                            // 本文が取得できなかった場合
                            if (retry < maxRetries) {
                                listener.onProgress(
                                    completedCount, totalFiles, fileInfo.name, currentChunkNum, totalChunks,
                                    "本文を取得できなかったため再試行中 ($retry/$maxRetries 回目)..."
                                )
                                delay(2000L + Random.nextLong(500, 1500))
                            }
                        }

                        // 3回試行しても本文が取得できなかった場合: このファイルを中断
                        if (chunkResult.isNullOrBlank()) {
                            fileSuccess = false
                            Log.e(TAG, "Chunk $currentChunkNum failed after $maxRetries retries for file ${fileInfo.name}")
                            break
                        }

                        // 元のチャンクの改行構造を保持
                        var formattedChunk = chunkResult
                        if (chunkText.endsWith("\n") && !formattedChunk.endsWith("\n")) {
                            formattedChunk += "\n"
                        }
                        if (chunkText.endsWith("\n\n") && !formattedChunk.endsWith("\n\n")) {
                            formattedChunk += "\n"
                        }

                        translatedChunks.add(formattedChunk)
                        lastChunkResultText = chunkResult

                        // チャンク間待機（範囲ランダム指定）
                        val actualChunkDelay = calculateDelayMs(chunkDelay, strategy.defaultChunkDelaySec)
                        delay(actualChunkDelay)
                    }

                    // ファイル全体の保存成否判定
                    if (fileSuccess && isRunning && translatedChunks.size == totalChunks) {
                        val combinedResult = translatedChunks.joinToString("")
                        Log.d(TAG, "Saving translated file ${fileInfo.name}: originalLength=${originalContent.length}, translatedLength=${combinedResult.length}")
                        val saveResult = fileStore.saveTranslatedFile(folderUri, fileInfo.name, combinedResult)
                        if (saveResult.isSuccess) {
                            completedCount++
                            consecutiveFailureCount = 0 // ★成功したので連続失敗カウントをリセット！
                            listener.onProgress(
                                completedCount, totalFiles, fileInfo.name, totalChunks, totalChunks,
                                "保存完了 ($currentFileNum/$totalFiles)"
                            )
                        } else {
                            fileSuccess = false
                            Log.e(TAG, "Failed to save translated file: ${fileInfo.name}", saveResult.exceptionOrNull())
                        }
                    }

                    // サーキットブレーカー判定
                    if (!fileSuccess && isRunning) {
                        consecutiveFailureCount++
                        failedFileNames.add(fileInfo.name)
                        Log.w(TAG, "File ${fileInfo.name} failed. Consecutive failures: $consecutiveFailureCount")

                        if (consecutiveFailureCount >= maxConsecutiveFailures) {
                            // ★連続2ファイル失敗: 単一ファイルではなく全体的な制限・Captcha・通信障害と判断して全体安全停止
                            val stopMsg = "${strategy.engineName}翻訳を停止しました: 連続して翻訳に失敗しました（制限・Captcha・通信障害の可能性があります）。Web版をご確認ください"
                            listener.onProgress(completedCount, totalFiles, fileInfo.name, 0, 0, stopMsg)
                            listener.onTaskFinished(false, stopMsg)
                            stop()
                            return@launch
                        } else {
                            // ★単一の失敗（1回目）: このファイルをスキップして後続ファイルへ前進
                            val skipMsg = "⚠️ ${fileInfo.name} をスキップして次へ進みます"
                            listener.onProgress(completedCount, totalFiles, fileInfo.name, 0, 0, skipMsg)
                            delay(1500L)
                        }
                    }

                    // 通信ゼロで Chromium の一時 RAM キャッシュをパージ（長時間稼働時のメモリ肥大化防止）
                    mainHandler.post {
                        if (isRunning) {
                            try { webView.clearCache(false) } catch (_: Exception) {}
                        }
                    }

                    // ファイル間待機（範囲ランダム指定）
                    val actualFileDelay = calculateDelayMs(fileDelay, strategy.defaultFileDelaySec)
                    delay(actualFileDelay)
                }

                if (isRunning) {
                    val finishMessage = if (failedFileNames.isEmpty()) {
                        "${strategy.engineName}: 全 $completedCount / $totalFiles 件の翻訳が完了しました"
                    } else {
                        "${strategy.engineName}: $completedCount / $totalFiles 件完了 (${failedFileNames.size}件スキップ: ${failedFileNames.joinToString(", ")})"
                    }
                    listener.onProgress(completedCount, totalFiles, "", 0, 0, finishMessage)
                    listener.onTaskFinished(true, finishMessage)
                }

            } catch (e: CancellationException) {
                listener.onTaskFinished(false, "${strategy.engineName}翻訳を中断しました")
            } catch (e: Exception) {
                Log.e(TAG, "BaseWebTranslationTask error", e)
                listener.onTaskFinished(false, "エラー: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private suspend fun waitForDomReady(view: WebView) {
        withTimeout(strategy.domReadyTimeoutMs) {
            while (isRunning) {
                if (unquoteJs(evalJs(view, strategy.checkDomReadyJs)) == "READY") return@withTimeout
                delay(500L)
            }
        }
    }

    /**
     * 単一チャンクの翻訳処理。
     * 本文が正常に確定取得できた場合はテキストを返し、取得できなければ null を返す（KISS原則）。
     */
    private suspend fun translateChunk(view: WebView, text: String, lastResultText: String): String? {
        if (!isRunning) return null

        // 1. クリア
        evalJs(view, strategy.clearInputJs)

        // 2. 結果エリアが空になるまで待機（最大4秒）
        val clearStart = System.currentTimeMillis()
        while (isRunning && System.currentTimeMillis() - clearStart < 4000L) {
            if (unquoteJs(evalJs(view, strategy.checkResultEmptyJs)) == "EMPTY") break
            delay(200L)
        }

        // 3. クリア後～フォーカスまでの人間的タイムラグ
        delay(Random.nextLong(350, 600))

        // 4. 入力枠へのフォーカス & 全選択
        evalJs(view, strategy.focusAndSelectJs)

        // 5. フォーカス後～Ctrl+V貼り付けまでの人間的タイムラグ
        delay(Random.nextLong(200, 450))

        // 6. ユーザーの手動貼り付けを完全再現するテキスト入力 & 確定
        val jsonText = json.encodeToString(text)
        val inputScript = strategy.buildPasteAndInputJs(jsonText)
        val inputStatus = unquoteJs(evalJs(view, inputScript))
        if (inputStatus != "OK") {
            Log.e(TAG, "Input failed: $inputStatus")
            return null
        }

        // 7. 入力直後、翻訳が開始するまで待機
        val delayRange = strategy.postInputDelayRange()
        delay(Random.nextLong(delayRange.first, delayRange.last))

        // 8. 翻訳結果の安定待機
        var currentCandidate = ""
        var stableCount = 0
        val maxWaitMs = strategy.resultWaitTimeoutMs
        val intervalMs = strategy.resultCheckIntervalMs
        val startTime = System.currentTimeMillis()

        while (isRunning && (System.currentTimeMillis() - startTime < maxWaitMs)) {
            delay(intervalMs)

            val domResult = parseDomResult(evalJs(view, strategy.getResultJs))

            if (domResult.status == "OK" && domResult.text.isNotBlank()) {
                // 前回チャンクの結果と同一なら、まだ新しい翻訳が反映されていない
                if (lastResultText.isNotEmpty() && domResult.text == lastResultText) {
                    stableCount = 0
                    continue
                }
                if (domResult.text == currentCandidate) {
                    stableCount++
                    if (stableCount >= strategy.requiredStableCount) return domResult.text
                } else {
                    currentCandidate = domResult.text
                    stableCount = 1
                }
            } else {
                stableCount = 0
            }
        }

        // タイムアウトしたが候補があれば使用
        return if (currentCandidate.isNotBlank() && currentCandidate != lastResultText) currentCandidate else null
    }

    private fun unquoteJs(raw: String): String {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return try { json.decodeFromString<String>(raw) } catch (_: Exception) { raw }
        }
        return raw
    }

    private fun parseDomResult(rawRes: String): DomTranslationResult {
        val cleanJson = unquoteJs(rawRes)
        return try {
            json.decodeFromString<DomTranslationResult>(cleanJson)
        } catch (_: Exception) {
            DomTranslationResult(status = "UNKNOWN", text = cleanJson)
        }
    }

    private suspend fun evalJs(view: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                view.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result ?: "")
                }
            }
        }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        job?.cancel()
        job = null
        mainHandler.post {
            try {
                webView.stopLoading()
                webView.pauseTimers()
                webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying translation WebView", e)
            }
        }
    }

    /**
     * "1.0-3.0" などの範囲文字列からランダムなミリ秒待機時間を算出する。
     */
    private fun calculateDelayMs(delayConfig: String, defaultSec: Double): Long {
        return try {
            if (delayConfig.contains("-")) {
                val parts = delayConfig.split("-")
                val minSec = parts[0].trim().toDoubleOrNull() ?: defaultSec
                val maxSec = parts[1].trim().toDoubleOrNull() ?: minSec
                val actualMin = minOf(minSec, maxSec)
                val actualMax = maxOf(minSec, maxSec)
                (Random.nextDouble(actualMin, actualMax) * 1000).toLong()
            } else {
                val sec = delayConfig.trim().toDoubleOrNull() ?: defaultSec
                (sec * 1000).toLong()
            }
        } catch (_: Exception) {
            (defaultSec * 1000).toLong()
        }
    }

    companion object {
        private const val TAG = "BaseWebTranslationTask"
    }
}