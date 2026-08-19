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

class DeeplTranslationTask(
    private val context: Context,
    private val folderUri: Uri,
    private val sourceLang: String = "auto",
    private val targetLang: String = "ja",
    private val listener: TranslationTask.TranslationListener
) {

    @Serializable
    private data class DomResult(
        val status: String = "",
        val text: String = "",
        val message: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileStore = TranslationFileStore(context, TranslationFileStore.DEEPL_OUTPUT_FOLDER)

    // バックグラウンド専用の独立したWebView（画面のCompose/Activityライフサイクルから完全に分離）
    private val webView = WebView(context.applicationContext)

    // Activityの破棄・バックグラウンド移行と連動してキャンセルされない独立したScope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    @Volatile
    private var isRunning = false

    companion object {
        private const val TAG = "DeeplTranslationTask"
        // DeepL無料Web版の文字数制限（1500文字）に合わせた安全マージンサイズ
        private const val MAX_CHUNK_SIZE = 1300
        private const val MAX_CHUNK_RETRIES = 2

        // ---- 事前構築済みJSスクリプト ----

        /** 入力エリアの存在確認（多重フォールバックセレクタ） */
        private const val JS_CHECK_DOM_READY = """(function(){var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea[aria-label*="Source"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');return el?"READY":"WAIT"})()"""

        /** クリアボタンクリック + 入力エリア直接クリアの二重保証 */
        private const val JS_CLEAR = """(function(){try{var clearBtn=document.querySelector('button[data-testid="translator-source-clear-button"]')||document.querySelector('button[aria-label*="Clear"]')||document.querySelector('button[aria-label*="消去"]')||document.querySelector('button[aria-label*="クリア"]');if(clearBtn)clearBtn.click();var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');if(el){if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){el.value='';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}else{var t=el.querySelector('[contenteditable="true"]')||el;t.innerText='';t.innerHTML='<p><br></p>';t.dispatchEvent(new Event('input',{bubbles:true}));t.dispatchEvent(new Event('change',{bubbles:true}))}}return"OK"}catch(e){return"ERROR: "+e.message}})()"""

        /** 結果エリアが空か確認 */
        private const val JS_CHECK_EMPTY = """(function(){var targetEl=document.querySelector('div[data-testid="translator-target-input"]')||document.querySelector('d-textarea[name="target"]')||document.querySelector('section[aria-label*="Translation"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Translation"]')||document.querySelector('div[aria-label*="訳文"]')||document.querySelector('#target-dummydiv');if(!targetEl)return"EMPTY";var text=targetEl.innerText||targetEl.textContent||'';return text.trim().length===0?"EMPTY":"NOT_EMPTY"})()"""

        /**
         * DeepL用手動貼り付け再現スクリプトテンプレート。
         * %s を JSONエンコード済みテキストで置換して使用する。
         * ClipboardEvent paste → InputEvent insertFromPaste の順で発火。
         */
        private const val JS_INPUT_TEMPLATE = """(function(){try{var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');if(!el)return"NO_INPUT_ELEMENT";var targetInput=(el.getAttribute('contenteditable')==='true')?el:(el.querySelector('[contenteditable="true"]')||el);targetInput.focus();if(targetInput.tagName==='TEXTAREA'||targetInput.tagName==='INPUT'){targetInput.select()}else{var range=document.createRange();range.selectNodeContents(targetInput);var sel=window.getSelection();sel.removeAllRanges();sel.addRange(range)}var text=%s;var dt=new DataTransfer();dt.setData('text/plain',text);var pe=new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt});targetInput.dispatchEvent(pe);var currentVal=(targetInput.tagName==='TEXTAREA'||targetInput.tagName==='INPUT')?targetInput.value:targetInput.innerText;if(!currentVal||currentVal.trim().length===0){if(targetInput.tagName==='TEXTAREA'||targetInput.tagName==='INPUT'){targetInput.value=text}else{targetInput.innerText=text}}targetInput.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:text}));targetInput.dispatchEvent(new Event('change',{bubbles:true}));return"OK"}catch(e){return"ERROR: "+e.message}})()"""

        /** 翻訳結果取得（エラー監視・プログレス監視・翻訳本文抽出） */
        private const val JS_GET_RESULT = """(function(){try{var alertEl=document.querySelector('div[role="alert"]')||document.querySelector('.lmt__alert');if(alertEl){var at=alertEl.innerText||'';if(at.indexOf("文字")!==-1||at.indexOf("character")!==-1||at.indexOf("制限")!==-1){return JSON.stringify({status:"LIMIT_ERROR",message:at})}}var loaders=document.querySelectorAll('div[role="progressbar"],div[class*="loading"],div[class*="spinner"],svg[class*="spin"]');for(var i=0;i<loaders.length;i++){var s=window.getComputedStyle(loaders[i]);if(s.display!=='none'&&s.visibility!=='hidden'&&loaders[i].offsetParent!==null){return JSON.stringify({status:"TRANSLATING",text:""})}}var targetEl=document.querySelector('div[data-testid="translator-target-input"]')||document.querySelector('d-textarea[name="target"]')||document.querySelector('section[aria-label*="Translation"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Translation"]')||document.querySelector('div[aria-label*="訳文"]')||document.querySelector('#target-dummydiv');if(targetEl){var tt=(targetEl.innerText||targetEl.textContent||'').trim();if(tt.length>0&&tt!=="翻訳"&&tt!=="Translation"&&tt.indexOf("翻訳中")===-1){return JSON.stringify({status:"OK",text:tt})}}return JSON.stringify({status:"WAITING",text:""})}catch(e){return JSON.stringify({status:"ERROR",message:e.message})}})()"""
    }

    init {
        // DeepLのWeb版はデスクトップ表示（PC用UserAgent）で確実にWebUIをロードする
        WebViewHelper.applyStandardSettings(webView, blockImages = false, isDesktop = true)
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
                listener.onProgress(0, totalFiles, "", 0, 0, "DeepL翻訳を初期化中...")

                val targetUrl = "https://www.deepl.com/ja/translator#$sourceLang/$targetLang/"

                // 独立した裏WebViewでDeepL翻訳を初期化
                if (webView.url?.contains("deepl.com") != true) {
                    val pageLoaded = CompletableDeferred<Boolean>()

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) {
                            if (url != null && !url.startsWith("javascript:") && !pageLoaded.isCompleted) {
                                pageLoaded.complete(true)
                            }
                        }
                    }

                    webView.loadUrl(targetUrl)

                    val isLoaded = withTimeoutOrNull(30000L) {
                        pageLoaded.await()
                    } ?: false

                    if (!isLoaded || !isRunning) {
                        throw IllegalStateException("DeepL翻訳のページ読み込みに失敗しました")
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
                            "DeepL翻訳中 ($currentFileNum/$totalFiles, チャンク $currentChunkNum/$totalChunks)"
                        )

                        // チャンク翻訳（リトライ対応 & 前世代重複防止）
                        var chunkResult: String? = null
                        for (retry in 0..MAX_CHUNK_RETRIES) {
                            if (!isRunning) break
                            chunkResult = translateChunk(webView, chunkText, lastChunkResultText)
                            if (chunkResult != null && chunkResult.isNotBlank()) break
                            Log.w(TAG, "Chunk $currentChunkNum retry $retry of file ${fileInfo.name}")
                            delay(2500L)
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
                        // DeepLのレートリミット対策でチャンク間に適切なインターバルを設ける
                        delay(2000L)
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

                    delay(1000L)
                }

                if (isRunning) {
                    val finishMessage = "DeepL: 全 $completedCount / $totalFiles 件の翻訳が完了しました"
                    listener.onProgress(completedCount, totalFiles, "", 0, 0, finishMessage)
                    listener.onTaskFinished(true, finishMessage)
                }

            } catch (e: CancellationException) {
                listener.onTaskFinished(false, "DeepL翻訳を中断しました")
            } catch (e: Exception) {
                Log.e(TAG, "DeeplTranslationTask error", e)
                listener.onTaskFinished(false, "エラー: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private suspend fun waitForDomReady(view: WebView) {
        withTimeout(25000L) {
            while (isRunning) {
                if (unquoteJs(evalJs(view, JS_CHECK_DOM_READY)) == "READY") return@withTimeout
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
        evalJs(view, JS_CLEAR)

        // 2. 結果エリアが空になるまで待機（最大4秒）
        val clearStart = System.currentTimeMillis()
        while (isRunning && System.currentTimeMillis() - clearStart < 4000L) {
            if (unquoteJs(evalJs(view, JS_CHECK_EMPTY)) == "EMPTY") break
            delay(200L)
        }
        delay(400L)

        // 3. ユーザーの手動貼り付けを完全再現するテキスト入力
        val jsonText = json.encodeToString(text)
        val inputScript = JS_INPUT_TEMPLATE.format(jsonText)
        val inputStatus = unquoteJs(evalJs(view, inputScript))
        if (inputStatus != "OK") {
            Log.e(TAG, "DeepL Input failed: $inputStatus")
            return null
        }

        // 入力直後、DeepL翻訳が開始するまで待機
        delay(2500L)

        // 4. 翻訳結果の安定待機
        var currentCandidate = ""
        var stableCount = 0
        val maxWaitMs = 50000L
        val intervalMs = 800L
        val startTime = System.currentTimeMillis()

        while (isRunning && (System.currentTimeMillis() - startTime < maxWaitMs)) {
            delay(intervalMs)

            val domResult = parseDomResult(evalJs(view, JS_GET_RESULT))

            if (domResult.status == "LIMIT_ERROR") {
                Log.e(TAG, "DeepL limit error: ${domResult.message}")
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
                    // 3回連続（約2.4秒間）安定したら翻訳完了と確定
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

    private fun unquoteJs(raw: String): String {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return try { json.decodeFromString<String>(raw) } catch (_: Exception) { raw }
        }
        return raw
    }

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
