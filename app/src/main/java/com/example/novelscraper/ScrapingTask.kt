package com.example.novelscraper

import android.annotation.SuppressLint
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
    private val taskListener: TaskListener
) {
    interface TaskListener {
        fun onStatusUpdate(task: ScrapingTask, status: String)
        fun onTaskFinished(task: ScrapingTask)
        fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String)
        fun onUpdateHistory(folderName: String, title: String, chapter: String, url: String, config: ScraperConfig)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val webView = WebView(context.applicationContext)
    
    var currentUrl = startUrl
    var lastSuccessUrl = ""
    var retryCount = 0
    var status = "準備中..."
    var folderName = "(取得中...)"
    var isRunning = true
    private var state = TaskState.INITIAL_CHECK

    private enum class TaskState { INITIAL_CHECK, FETCHING_FOLDER, RETURNING, SCRAPING }

    init {
        WebViewHelper.applyStandardSettings(webView, !useImages)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isRunning) return
                val loadedUrl = url ?: return
                currentUrl = loadedUrl

                val title = view?.title ?: ""
                verifyTurnstile(view)

                if (isCloudflareTitle(title)) {
                    triggerCFWait()
                    return
                }
                
                if (state == TaskState.SCRAPING) {
                    performHumanLikeScroll(view)
                }

                scope.launch {
                    val delayMs = if (state == TaskState.SCRAPING) 5000L else 2000L
                    delay(delayMs)
                    if (!isRunning) return@launch
                    
                    when (state) {
                        TaskState.INITIAL_CHECK -> checkFolderLink()
                        TaskState.FETCHING_FOLDER -> fetchFolderName()
                        TaskState.RETURNING -> { state = TaskState.SCRAPING; processPage() }
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
        val js = """
            (function() {
                function findAndClickCheckbox(root) {
                    if (!root) return;
                    var shadow = root.shadowRoot;
                    var target = shadow ? shadow : root;
                    var inputs = target.querySelectorAll('input[type="checkbox"]');
                    for (var i = 0; i < inputs.length; i++) {
                        if (!inputs[i].checked) {
                            inputs[i].click();
                        }
                    }
                    var children = target.children;
                    for (var i = 0; i < children.length; i++) {
                        findAndClickCheckbox(children[i]);
                    }
                }
                if (!window.cfClickerStarted) {
                    window.cfClickerStarted = true;
                    setInterval(function() { findAndClickCheckbox(document.body); }, 1500);
                }
            })();
        """
        view?.evaluateJavascript(js, null)
    }

    private fun performHumanLikeScroll(view: WebView?) {
        val js = """
            (function() {
                var totalHeight = document.body.scrollHeight;
                var currentScroll = window.scrollY;
                function humanScroll() {
                    if (currentScroll >= totalHeight - window.innerHeight) return;
                    var step = Math.floor(Math.random() * 60) + 20;
                    if (Math.random() < 0.05) step = -step; 
                    var delay = Math.floor(Math.random() * 150) + 50;
                    if (Math.random() < 0.03) delay += 1500;
                    currentScroll += step;
                    if (currentScroll < 0) currentScroll = 0; 
                    window.scrollTo(0, currentScroll);
                    setTimeout(humanScroll, delay);
                }
                humanScroll();
            })();
        """
        view?.evaluateJavascript(js, null)
    }

    private fun isCloudflareTitle(title: String): Boolean {
        return title.contains("Just a moment") || title.contains("Cloudflare") || title.contains("Verify")
    }

    private fun triggerCFWait() {
        updateStatus("⚠️ 認証待機中...")
        scope.launch {
            delay(30000)
            if (isRunning) webView.reload()
        }
    }

    fun start() {
        updateStatus("開始: リンク確認中")
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
        if (retryCount < 3) {
            retryCount++
            updateStatus("リトライ($retryCount): $reason")
            scope.launch {
                delay(60000)
                if (isRunning) webView.loadUrl(currentUrl)
            }
        } else {
            updateStatus("エラー停止: $reason")
            isRunning = false
            taskListener.onTaskFinished(this)
        }
    }

    private fun checkFolderLink() {
        val folderLinkSel = config.folderLink
        if (folderLinkSel.isEmpty()) { fetchFolderNameInPlace(); return }
        val js = "(function(){ var el = document.querySelector('$folderLinkSel'); return el ? el.href : ''; })();"
        webView.evaluateJavascript(js) { res ->
            if (!isRunning) return@evaluateJavascript
            val link = res?.replace("\"", "") ?: ""
            if (link.isNotEmpty() && link != "null" && link != "undefined") {
                updateStatus("作品名取得へ移動...")
                state = TaskState.FETCHING_FOLDER
                webView.loadUrl(link)
            } else { fetchFolderNameInPlace() }
        }
    }

    private fun fetchFolderName() {
        val folderSel = config.folder
        val folderRegex = config.regex
        val js = "(function(){ var el = document.querySelector('$folderSel'); return el ? el.innerText.trim() : ''; })();"
        webView.evaluateJavascript(js) { res ->
            if (!isRunning) return@evaluateJavascript
            var name = res?.replace("\"", "") ?: ""
            name = cleanText(name.replace("\\u003C", "<"), folderRegex)
            if (name.isNotEmpty()) { folderName = name; updateStatus("作品名取得: $folderName") }
            else { updateStatus("作品名取得失敗(維持)") }
            state = TaskState.RETURNING
            webView.loadUrl(startUrl)
        }
    }

    private fun fetchFolderNameInPlace() {
        val folderSel = config.folder
        val folderRegex = config.regex
        if (folderSel.startsWith("@")) { folderName = folderSel.substring(1) }
        else if (folderSel.isNotEmpty()) {
            val js = "(function(){ var el = document.querySelector('$folderSel'); return el ? el.innerText.trim() : ''; })();"
            webView.evaluateJavascript(js) { res ->
                var name = res?.replace("\"", "") ?: ""
                name = cleanText(name.replace("\\u003C", "<"), folderRegex)
                if (name.isNotEmpty()) folderName = name
            }
        }
        state = TaskState.SCRAPING
        processPage()
    }

    private fun processPage() {
        if (!isRunning || currentUrl == lastSuccessUrl) return

        val bodySel = config.body.replace("'", "\\'")
        val titleSel = config.title.replace("'", "\\'")
        val nextSel = config.next.replace("'", "\\'")
        val chapterSel = config.chapter.replace("'", "\\'")
        val chapterRegex = config.chapterRegex.replace("\\", "\\\\").replace("'", "\\'")

        val jsCode = """
            (function() {
                try {
                    if (document.title.includes("Just a moment") || document.body.innerText.includes("Verify you are human")) return "CF_DETECTED";
                    
                    var result = { title: "", content: "", nextUrl: "", chapter: "", folderName: "" };
                    
                    // --- 構造化データ(JSON-LD)の解析 ---
                    var metaData = {};
                    try {
                        var ldJsons = document.querySelectorAll('script[type="application/ld+json"]');
                        for (var i = 0; i < ldJsons.length; i++) {
                            var parsed = JSON.parse(ldJsons[i].innerText);
                            if (Array.isArray(parsed)) parsed = parsed[0];
                            if (parsed.headline || (parsed.isPartOf && parsed.isPartOf.name)) {
                                metaData = parsed;
                                break;
                            }
                        }
                    } catch(e){}

                    // --- 小説タイトル(フォルダ名用) ---
                    var folderTitle = "";
                    var novelTitleElem = document.querySelector('.series-title, .novel_title, .novel-title, .p-novel__title');
                    if (novelTitleElem) {
                        folderTitle = novelTitleElem.innerText.trim();
                    } else {
                        var breadcrumbs = document.querySelectorAll('.breadcrumb, [class*="breadcrumb"], .p-breadcrumb, #breadcrumbs');
                        if (breadcrumbs.length > 0) {
                            var links = Array.from(breadcrumbs[0].querySelectorAll('a')).map(a => a.innerText.trim()).filter(t => t && t.length > 1 && !t.match(/ホーム|トップ|Home|Top/i));
                            if (links.length > 0) {
                                folderTitle = links[links.length - 1];
                                if (folderTitle.match(/第?\d+|話|章|節|Part|ページ/i) && links.length >= 2) folderTitle = links[links.length - 2];
                            }
                        }
                    }
                    if (!folderTitle && metaData.isPartOf) folderTitle = metaData.isPartOf.name || metaData.isPartOf;
                    if (!folderTitle) folderTitle = document.querySelector('meta[property="og:site_name"]')?.content;
                    
                    var titleParts = document.title.split(/\s*[-|｜]\s*/).filter(p => p);
                    if (!folderTitle && titleParts.length >= 2) {
                        folderTitle = titleParts.length >= 3 ? titleParts[titleParts.length - 2] : titleParts[titleParts.length - 1];
                    }
                    result.folderName = typeof folderTitle === 'string' ? folderTitle.trim() : "";

                    // --- チャプター名の抽出 ---
                    var titleElem = '$titleSel' !== "" ? document.querySelector('$titleSel') : null;
                    if (!titleElem) titleElem = document.querySelector('.novel_subtitle, .ep-title, .episode-title, .chapter-title, .widget-title, h1.text-xl, h1, h2.chapter-title, .entry-title');
                    result.title = titleElem ? titleElem.innerText.trim() : "";
                    
                    if (!result.title) {
                        if (metaData.headline) {
                            result.title = metaData.headline;
                        } else if (titleParts.length > 0) {
                            result.title = titleParts[0].trim();
                        } else {
                            result.title = document.title.trim();
                        }
                    }

                    // --- チャプター番号の抽出 ---
                    var chapNum = "";
                    if ('$chapterSel' !== "") {
                        var text = "";
                        if ('$chapterSel' === "@URL") {
                            text = location.href;
                        } else {
                            var chapElem = document.querySelector('$chapterSel');
                            if (chapElem) text = chapElem.innerText.trim();
                        }
                        if (text) {
                            if ('$chapterRegex' !== "") {
                                try { var re = new RegExp('$chapterRegex'); var match = text.match(re); if(match) chapNum = match[1] || match[0]; } catch(e){}
                            } 
                            if (!chapNum) { var m = text.match(/(\d+)/); if(m) chapNum = m[1]; }
                        }
                    }
                    if (!chapNum) {
                        var m = result.title.match(/(?:第)?\s*(\d+)\s*(?:話|章|部分|ページ|回|節)/) || result.title.match(/(\d+)/) || location.href.match(/[/-](\d+)\/?($|\?|#)/);
                        if(m) chapNum = m[1];
                    }
                    result.chapter = chapNum ? chapNum.padStart(4, '0') : "";

                    // --- 本文の抽出 ---
                    function extractSmartContent() {
                        var bodyElem = '$bodySel' !== "" ? document.querySelector('$bodySel') : null;
                        if (!bodyElem) {
                            var candidates = Array.from(document.querySelectorAll('div, article, section, main')).map(el => {
                                var pTags = el.querySelectorAll('p');
                                var textLength = el.innerText.replace(/\s+/g, '').length;
                                var score = textLength + (pTags.length * 40) + (el.querySelectorAll('br').length * 10);
                                if (el.className.match(/nav|menu|footer|side|ad-|comment/i)) score /= 5;
                                return { el: el, score: score };
                            }).sort((a, b) => b.score - a.score);
                            bodyElem = candidates[0] && candidates[0].score > 100 ? candidates[0].el : null;
                        }
                        if (!bodyElem) return "";
                        var clone = bodyElem.cloneNode(true);
                        clone.querySelectorAll('script, style, noscript, iframe, template, .ad, .ads, [class*="advertisement"], .social, .share, #comments').forEach(n => n.remove());
                        if (!${useImages}) clone.querySelectorAll('img, picture, svg').forEach(n => n.remove());
                        
                        // 改行を確実に保持するための処理
                        clone.querySelectorAll('br').forEach(br => {
                            var nl = document.createTextNode('\n');
                            br.parentNode.replaceChild(nl, br);
                        });
                        // ブロック要素の間に改行を挿入
                        clone.querySelectorAll('p, div, h1, h2, h3, h4, h5, h6, li, dt, dd').forEach(el => {
                            if (el.innerText.trim().length > 0) {
                                el.after(document.createTextNode('\n'));
                            }
                        });
                        
                        return clone.innerText.trim().replace(/\n\s*\n/g, '\n\n');
                    }
                    result.content = extractSmartContent();

                    // --- 「次へ」リンクの抽出 ---
                    var nextElem = '$nextSel' !== "" ? document.querySelector('$nextSel') : document.querySelector('a[rel="next"]');
                    if (!nextElem) {
                        var allLinks = Array.from(document.querySelectorAll('a'));
                        var nextCandidates = allLinks.filter(a => {
                            var t = a.innerText.trim();
                            return t.length < 15 && (t.match(/^(?:次|Next|続く|>>|＞＞|次へ|次の話)/i));
                        });
                        if (nextCandidates.length > 1) {
                            var curPath = location.pathname;
                            nextCandidates.sort((a, b) => {
                                var scoreA = a.href.includes(curPath.substring(0, curPath.lastIndexOf('/'))) ? 1 : 0;
                                var scoreB = b.href.includes(curPath.substring(0, curPath.lastIndexOf('/'))) ? 1 : 0;
                                return scoreB - scoreA;
                            });
                        }
                        nextElem = nextCandidates[0];
                    }
                    result.nextUrl = nextElem ? nextElem.href : "";
                    
                    return JSON.stringify(result);
                } catch(e) { return "JS_ERROR: " + e.message; }
            })();
        """

        webView.evaluateJavascript(jsCode) { jsonResult ->
            if (!isRunning) return@evaluateJavascript
            try {
                if (jsonResult == null || jsonResult == "null") { handleTaskRetry("解析失敗(null)"); return@evaluateJavascript }
                
                // JavaScriptの結果をJSONとしてパース
                val rawResult = Json.decodeFromString<String>(jsonResult)
                if (rawResult == "CF_DETECTED") { triggerCFWait(); return@evaluateJavascript }
                if (rawResult.startsWith("JS_ERROR")) { handleTaskRetry(rawResult); return@evaluateJavascript }

                val data = Json.decodeFromString<ScrapingResult>(rawResult)
                val title = data.title.ifEmpty { "無題" }
                val content = data.content
                val nextUrl = data.nextUrl
                var chapNum = data.chapter

                // --- 1. URLから数字を抽出 (任意の場所から一番最後の数字の塊を取得) ---
                if (chapNum.isEmpty()) {
                    val urlNumbers = Regex("(\\d+)").findAll(currentUrl).map { it.value }.toList()
                    if (urlNumbers.isNotEmpty()) {
                        chapNum = urlNumbers.last().padStart(4, '0')
                    }
                }

                if (content.length < 20) { handleTaskRetry("本文短過"); return@evaluateJavascript }

                val safeTitle = cleanText(title, config.fileRegex)
                taskListener.onSaveResult(folderName, safeTitle, content, chapNum)

                if (folderName != "(取得中...)") {
                    taskListener.onUpdateHistory(folderName, safeTitle, chapNum, currentUrl, config)
                }

                lastSuccessUrl = currentUrl
                retryCount = 0
                updateStatus("保存: ${if(chapNum.isNotEmpty()) "$chapNum " else ""}${safeTitle.take(10)}...")

                val shouldStop = isEndDetected(nextUrl, title)

                if (nextUrl.isNotEmpty() && nextUrl != "null" && !shouldStop) {
                    val delayMs = calculateDelay(config.delay)
                    updateStatus("待機(${delayMs/1000}s): $folderName")
                    scope.launch {
                        delay(delayMs)
                        if (isRunning) webView.loadUrl(nextUrl)
                    }
                } else {
                    updateStatus(if(shouldStop) "終了検知: $folderName" else "完了: $folderName")
                    isRunning = false
                    taskListener.onTaskFinished(this)
                }
            } catch (e: Exception) { handleTaskRetry("エラー: ${e.message}") }
        }
    }

    private fun isEndDetected(nextUrl: String, title: String): Boolean {
        if (config.endCheck.isEmpty()) return false
        val regex = Regex(config.endCheck)
        return regex.containsMatchIn(nextUrl) || 
               regex.containsMatchIn(title) || 
               nextUrl.contains("/null") || 
               nextUrl.endsWith("null")
    }

    private fun calculateDelay(delayStr: String): Long {
        return try {
            if (delayStr.contains("-")) {
                val parts = delayStr.split("-")
                val min = parts[0].trim().toLongOrNull() ?: 2L
                val max = parts[1].trim().toLongOrNull() ?: min
                Random.nextLong(min, max + 1) * 1000
            } else {
                (delayStr.toLongOrNull() ?: 2L) * 1000
            }
        } catch (e: Exception) { 2000L }
    }

    private fun updateStatus(newStatus: String) {
        status = newStatus
        taskListener.onStatusUpdate(this, status)
    }

    private fun cleanText(original: String, pattern: String): String {
        return if (pattern.isNotEmpty()) {
            try { original.replace(Regex(pattern), "") } catch (e: Exception) { original }
        } else {
            original
        }.trim()
    }
}
