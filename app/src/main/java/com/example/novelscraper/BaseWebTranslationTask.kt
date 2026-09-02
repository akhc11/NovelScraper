package com.example.novelscraper

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Webバックグラウンド翻訳タスクの共通基底クラス。
 * Google 翻訳・DeepL 翻訳などの共通ワークフロー（DOM制御、ファイル処理、チャンク分割、リトライ、ディレイ計算）を集約し、
 * DRY (Don't Repeat Yourself) を達成する。
 */
open class BaseWebTranslationTask(
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

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileStore = TranslationFileStore(context, strategy.outputFolderName)

    // バックグラウンド専用の独立したWebView（画面のCompose/Activityライフサイクルから完全に分離）
    private val webView = WebView(context.applicationContext)

    // Activityの破棄・バックグラウンド移行と連動してキャンセルされない独立したScope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    @Volatile
    private var isRunning = false

    init {
        WebViewHelper.applyStandardSettings(webView, blockImages = false, isDesktop = strategy.isDesktop)
        WebViewHelper.applyVirtualSize(webView) // ヘッドレス（0x0）判定を解除
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
                    }

                    webView.loadUrl(targetUrl)

                    val isLoaded = withTimeoutOrNull(strategy.pageLoadTimeoutMs) {
                        pageLoaded.await()
                    } ?: false

                    if (!isLoaded || !isRunning) {
                        throw IllegalStateException("${strategy.engineName}翻訳のページ読み込みに失敗しました")
                    }
                }

                // 入力欄がDOMに出現するまで待機
                waitForDomReady(webView)

                var completedCount = 0

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
                        continue
                    }

                    val originalContent = readResult.getOrDefault("")
                    if (originalContent.isEmpty()) {
                        fileStore.saveTranslatedFile(folderUri, fileInfo.name, "")
                        completedCount++
                        listener.onProgress(
                            completedCount, totalFiles, fileInfo.name, 1, 1,
                            "空ファイルを保存完了 ($currentFileNum/$totalFiles)"
                        )
                        continue
                    }

                    val chunks = TextChunker.splitIntoChunks(originalContent, strategy.maxChunkSize)
                    val totalChunks = chunks.size
                    val translatedChunks = mutableListOf<String>()
                    var fileSuccess = true
                    var lastChunkResultText = ""

                    Log.d(TAG, "Starting file ${fileInfo.name}: totalLength=${originalContent.length}, totalChunks=$totalChunks")

                    for ((chunkIndex, chunkText) in chunks.withIndex()) {
                        if (!isRunning) { fileSuccess = false; break }

                        val currentChunkNum = chunkIndex + 1
                        listener.onProgress(
                            completedCount, totalFiles, fileInfo.name, currentChunkNum, totalChunks,
                            "${strategy.engineName}翻訳中 ($currentFileNum/$totalFiles, チャンク $currentChunkNum/$totalChunks)"
                        )

                        // チャンク翻訳（リトライ対応 & 前世代重複防止）
                        var chunkResult: String? = null
                        for (retry in 0..MAX_CHUNK_RETRIES) {
                            if (!isRunning) break
                            chunkResult = translateChunk(webView, chunkText, lastChunkResultText)
                            if (chunkResult != null && chunkResult.isNotBlank()) break
                            Log.w(TAG, "Chunk $currentChunkNum retry $retry of file ${fileInfo.name}")
                            delay(2000L + Random.nextLong(0, 500))
                        }

                        if (chunkResult == null || chunkResult.isBlank()) {
                            fileSuccess = false
                            Log.e(TAG, "Translation failed for chunk $currentChunkNum of file ${fileInfo.name}")
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

                    if (fileSuccess && isRunning && translatedChunks.size == totalChunks) {
                        val combinedResult = translatedChunks.joinToString("")
                        Log.d(TAG, "Saving translated file ${fileInfo.name}: originalLength=${originalContent.length}, translatedLength=${combinedResult.length}")
                        val saveResult = fileStore.saveTranslatedFile(folderUri, fileInfo.name, combinedResult)
                        if (saveResult.isSuccess) {
                            completedCount++
                            listener.onProgress(
                                completedCount, totalFiles, fileInfo.name, totalChunks, totalChunks,
                                "保存完了 ($currentFileNum/$totalFiles)"
                            )
                        } else {
                            Log.e(TAG, "Failed to save translated file: ${fileInfo.name}", saveResult.exceptionOrNull())
                        }
                    } else {
                        Log.e(TAG, "File ${fileInfo.name} failed or cancelled. translatedChunks=${translatedChunks.size}/$totalChunks")
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
                    val finishMessage = "${strategy.engineName}: 全 $completedCount / $totalFiles 件の翻訳が完了しました"
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

            if (domResult.status == "LIMIT_ERROR") {
                Log.e(TAG, "Limit error: ${domResult.message}")
                return null
            }

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
            DomTranslationResult(status = "WAITING")
        }
    }

    private suspend fun evalJs(view: WebView, script: String): String = withTimeoutOrNull(10000L) {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                if (!isRunning) {
                    if (cont.isActive) cont.resumeWith(Result.success("CANCELLED"))
                    return@post
                }
                view.evaluateJavascript(script) { result ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(result ?: "null"))
                    }
                }
            }
        }
    } ?: "TIMEOUT"

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        mainHandler.post {
            try {
                webView.stopLoading()
                webView.webViewClient = object : WebViewClient() {}
                webView.webChromeClient = null
                webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying webView", e)
            }
        }
    }

    companion object {
        private const val TAG = "BaseWebTranslationTask"
        private const val MAX_CHUNK_RETRIES = 2

        /**
         * 範囲指定（"30-80" や "1-3"）または単一指定からランダムな待機時間（ミリ秒）を計算する。
         */
        fun calculateDelayMs(delayStr: String, defaultSec: Double = 2.0): Long {
            return try {
                if (delayStr.contains("-")) {
                    val parts = delayStr.split("-")
                    val min = parts[0].trim().toDoubleOrNull() ?: defaultSec
                    val max = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: min
                    val actualMin = min.coerceAtLeast(0.1)
                    val actualMax = max.coerceAtLeast(actualMin)
                    val randomSec = if (actualMin >= actualMax) actualMin else Random.nextDouble(actualMin, actualMax)
                    (randomSec * 1000).toLong()
                } else {
                    val sec = delayStr.toDoubleOrNull() ?: defaultSec
                    val jitter = Random.nextDouble(-0.3, 0.3)
                    ((sec + jitter).coerceAtLeast(0.1) * 1000).toLong()
                }
            } catch (_: Exception) {
                (defaultSec * 1000).toLong()
            }
        }
    }
}
