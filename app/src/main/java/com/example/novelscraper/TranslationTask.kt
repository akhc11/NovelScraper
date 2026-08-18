package com.example.novelscraper

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TranslationTask(
    private val context: Context,
    private val folderUri: Uri,
    private val sourceLang: String = "auto",
    private val targetLang: String = "ja",
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
        fun onFileTranslated(fileName: String, success: Boolean)
        fun onTaskFinished(success: Boolean, message: String)
    }

    @Serializable
    private data class DomResult(
        val status: String = "",
        val text: String = "",
        val message: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileStore = TranslationFileStore(context)

    // バックグラウンド専用の独立したWebView（画面のCompose/Activityライフサイクルから完全に分離）
    private val webView = WebView(context.applicationContext)

    // Activityの破棄・バックグラウンド移行と連動してキャンセルされない独立したScope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    @Volatile
    private var isRunning = false

    companion object {
        private const val TAG = "TranslationTask"
        private const val MAX_CHUNK_SIZE = 3500
        private const val MAX_CHUNK_RETRIES = 2

        // ---- 事前構築済みJSスクリプト（GC負荷削減） ----

        /** textareaの存在確認 */
        private const val JS_CHECK_DOM_READY = """(function(){var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');return ta?"READY":"WAIT"})()"""

        /** クリアボタンクリック + textarea直接クリアの二重保証 */
        private const val JS_CLEAR = """(function(){try{var b=document.querySelector('button[aria-label*="Clear"],button[aria-label*="消去"],button[aria-label*="クリア"],button[jsname="RPbQ0e"]');if(b)b.click();var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');if(ta){ta.value='';ta.dispatchEvent(new Event('input',{bubbles:true}));ta.dispatchEvent(new Event('change',{bubbles:true}))}return"OK"}catch(e){return"ERROR: "+e.message}})()"""

        /** 結果エリアが空か確認 */
        private const val JS_CHECK_EMPTY = """(function(){var s=document.querySelectorAll('span[jsname="W297wb"],span[jsname="jqKxS"]');if(!s||s.length===0)return"EMPTY";var t="";for(var i=0;i<s.length;i++)t+=(s[i].innerText||s[i].textContent||'');return t.trim().length===0?"EMPTY":"NOT_EMPTY"})()"""

        /**
         * テキスト入力スクリプトテンプレート。
         * %s を JSONエンコード済みテキストで置換して使用する。
         * ClipboardEvent paste → InputEvent insertFromPaste の順で
         * ユーザーの手動貼り付けと同一のイベントシーケンスを再現する。
         */
        private const val JS_INPUT_TEMPLATE = """(function(){try{var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');if(!ta)return"NO_TEXTAREA";ta.focus();ta.select();var dt=new DataTransfer();dt.setData('text/plain',%s);var pe=new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt});ta.dispatchEvent(pe);if(!ta.value||ta.value.trim().length===0)ta.value=%s;ta.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:%s}));ta.dispatchEvent(new Event('change',{bubbles:true}));return"OK"}catch(e){return"ERROR: "+e.message}})()"""

        /** 翻訳結果取得（プログレスバーチェック + 純粋な翻訳本文spanのみ抽出） */
        private const val JS_GET_RESULT = """(function(){try{var pb=document.querySelectorAll('div[role="progressbar"],div[aria-valuemin]');for(var i=0;i<pb.length;i++){var s=window.getComputedStyle(pb[i]);if(s.display!=='none'&&s.visibility!=='hidden'&&pb[i].offsetParent!==null)return JSON.stringify({status:"TRANSLATING",text:""})}var sp=document.querySelectorAll('span[jsname="W297wb"]');if(!sp||sp.length===0)sp=document.querySelectorAll('span[jsname="jqKxS"]');if(sp&&sp.length>0){var tp=[];for(var i=0;i<sp.length;i++){var t=sp[i].innerText||sp[i].textContent||'';if(t&&t.indexOf("翻訳結果を利用できます")===-1&&t.indexOf("Translation result")===-1&&t.indexOf("翻訳中")===-1)tp.push(t)}var c=tp.join('');if(c.trim().length>0)return JSON.stringify({status:"OK",text:c})}return JSON.stringify({status:"WAITING",text:""})}catch(e){return JSON.stringify({status:"ERROR",message:e.message})}})()"""
    }

    init {
        WebViewHelper.applyStandardSettings(webView, blockImages = false, isDesktop = false)
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
                listener.onProgress(0, totalFiles, "", 0, 0, "Google翻訳を初期化中...")

                val targetUrl = "https://translate.google.com/?sl=$sourceLang&tl=$targetLang&op=translate"

                // 独立した裏WebViewでGoogle翻訳を初期化
                if (webView.url?.contains("translate.google.com") != true) {
                    val pageLoaded = CompletableDeferred<Boolean>()

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) {
                            if (url != null && !url.startsWith("javascript:") && !pageLoaded.isCompleted) {
                                pageLoaded.complete(true)
                            }
                        }
                    }

                    webView.loadUrl(targetUrl)

                    val isLoaded = withTimeoutOrNull(25000L) {
                        pageLoaded.await()
                    } ?: false

                    if (!isLoaded || !isRunning) {
                        throw IllegalStateException("Google翻訳のページ読み込みに失敗しました")
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
                        listener.onFileTranslated(fileInfo.name, false)
                        continue
                    }

                    val originalContent = readResult.getOrDefault("")
                    if (originalContent.isEmpty()) {
                        fileStore.saveTranslatedFile(folderUri, fileInfo.name, "")
                        completedCount++
                        listener.onFileTranslated(fileInfo.name, true)
                        listener.onProgress(
                            completedCount, totalFiles, fileInfo.name, 1, 1,
                            "空ファイルを保存完了 ($currentFileNum/$totalFiles)"
                        )
                        continue
                    }

                    val chunks = TextChunker.splitIntoChunks(originalContent, MAX_CHUNK_SIZE)
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
                            "翻訳中 ($currentFileNum/$totalFiles, チャンク $currentChunkNum/$totalChunks)"
                        )

                        // チャンク翻訳（リトライ対応 & 前世代重複防止）
                        var chunkResult: String? = null
                        for (retry in 0..MAX_CHUNK_RETRIES) {
                            if (!isRunning) break
                            chunkResult = translateChunk(webView, chunkText, lastChunkResultText)
                            if (chunkResult != null && chunkResult.isNotBlank()) break
                            Log.w(TAG, "Chunk $currentChunkNum retry $retry of file ${fileInfo.name}")
                            delay(2000L)
                        }

                        if (chunkResult == null || chunkResult.isBlank()) {
                            fileSuccess = false
                            Log.e(TAG, "Translation failed for chunk $currentChunkNum of file ${fileInfo.name}")
                            break
                        }

                        // 元のチャンクが改行で終わっていた場合、翻訳結果末尾にも改行を保持
                        var formattedChunk = chunkResult
                        if (chunkText.endsWith("\n") && !formattedChunk.endsWith("\n")) {
                            formattedChunk += "\n"
                        }
                        if (chunkText.endsWith("\n\n") && !formattedChunk.endsWith("\n\n")) {
                            formattedChunk += "\n"
                        }

                        translatedChunks.add(formattedChunk)
                        lastChunkResultText = chunkResult
                        delay(1200L)
                    }

                    if (fileSuccess && isRunning && translatedChunks.size == totalChunks) {
                        val combinedResult = translatedChunks.joinToString("")
                        Log.d(TAG, "Saving translated file ${fileInfo.name}: originalLength=${originalContent.length}, translatedLength=${combinedResult.length}")
                        val saveResult = fileStore.saveTranslatedFile(folderUri, fileInfo.name, combinedResult)
                        if (saveResult.isSuccess) {
                            completedCount++
                            listener.onFileTranslated(fileInfo.name, true)
                            listener.onProgress(
                                completedCount, totalFiles, fileInfo.name, totalChunks, totalChunks,
                                "保存完了 ($currentFileNum/$totalFiles)"
                            )
                        } else {
                            listener.onFileTranslated(fileInfo.name, false)
                            Log.e(TAG, "Failed to save translated file: ${fileInfo.name}", saveResult.exceptionOrNull())
                        }
                    } else {
                        listener.onFileTranslated(fileInfo.name, false)
                        Log.e(TAG, "File ${fileInfo.name} failed or cancelled. translatedChunks=${translatedChunks.size}/$totalChunks")
                    }

                    delay(800L)
                }

                if (isRunning) {
                    val finishMessage = "全 $completedCount / $totalFiles 件の翻訳が完了しました"
                    listener.onProgress(completedCount, totalFiles, "", 0, 0, finishMessage)
                    listener.onTaskFinished(true, finishMessage)
                }

            } catch (e: CancellationException) {
                listener.onTaskFinished(false, "翻訳を中断しました")
            } catch (e: Exception) {
                Log.e(TAG, "TranslationTask error", e)
                listener.onTaskFinished(false, "エラー: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private suspend fun waitForDomReady(view: WebView) {
        withTimeout(20000L) {
            while (isRunning) {
                if (unquoteJs(evalJs(view, JS_CHECK_DOM_READY)) == "READY") return@withTimeout
                delay(500L)
            }
        }
    }

    /**
     * 単一チャンクの翻訳処理。ユーザーが手動で貼り付けるのと全く同じ動作を再現する。
     *
     * 1. クリアボタンクリック（手動操作と同一）
     * 2. 結果エリアが空になるまで待機
     * 3. ClipboardEvent paste → InputEvent insertFromPaste でユーザー操作を再現
     * 4. 翻訳完了（プログレスバー消滅 & 翻訳本文の安定）を待機
     * 5. 前チャンクの結果と同一ならスキップ（重複防止）
     */
    private suspend fun translateChunk(view: WebView, text: String, lastResultText: String): String? {
        if (!isRunning) return null

        // 1. クリア
        evalJs(view, JS_CLEAR)

        // 2. 結果エリアが空になるまで待機（最大4秒）
        val clearStart = System.currentTimeMillis()
        while (isRunning && System.currentTimeMillis() - clearStart < 4000L) {
            if (unquoteJs(evalJs(view, JS_CHECK_EMPTY)) == "EMPTY") break
            delay(200L)
        }
        delay(300L)

        // 3. ユーザーの手動貼り付けを完全再現するテキスト入力
        val jsonText = json.encodeToString(text)
        val inputScript = JS_INPUT_TEMPLATE.format(jsonText, jsonText, jsonText)
        val inputStatus = unquoteJs(evalJs(view, inputScript))
        if (inputStatus != "OK") {
            Log.e(TAG, "Input failed: $inputStatus")
            return null
        }

        // 入力直後、Google翻訳が翻訳を開始するまで待機
        delay(2000L)

        // 4. 翻訳結果の安定待機
        var currentCandidate = ""
        var stableCount = 0
        val maxWaitMs = 45000L
        val intervalMs = 700L
        val startTime = System.currentTimeMillis()

        while (isRunning && (System.currentTimeMillis() - startTime < maxWaitMs)) {
            delay(intervalMs)

            val domResult = parseDomResult(evalJs(view, JS_GET_RESULT))

            if (domResult.status == "OK" && domResult.text.isNotBlank()) {
                // 前回チャンクの結果と同一なら、まだ新しい翻訳が反映されていない
                if (lastResultText.isNotEmpty() && domResult.text == lastResultText) {
                    stableCount = 0
                    continue
                }
                if (domResult.text == currentCandidate) {
                    stableCount++
                    // 3回連続（約2.1秒間）安定したら翻訳完了と確定
                    if (stableCount >= 3) return domResult.text
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

    // ---- ヘルパー ----

    /** evaluateJavascriptの戻り値は常にJSON文字列でエスケープされるため、外側の引用符を剥がす */
    private fun unquoteJs(raw: String): String {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return try { json.decodeFromString<String>(raw) } catch (_: Exception) { raw }
        }
        return raw
    }

    /** JSから取得したJSON文字列をDomResultにパースする。二重エスケープを自動解除。 */
    private fun parseDomResult(rawRes: String): DomResult {
        val cleanJson = unquoteJs(rawRes)
        return try {
            json.decodeFromString<DomResult>(cleanJson)
        } catch (_: Exception) {
            DomResult(status = "WAITING")
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
                webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying webView", e)
            }
        }
    }
}
