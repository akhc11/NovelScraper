package com.example.novelscraper

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class ExtractedTextItem(
    val id: Int,
    val text: String
)

@Serializable
data class TranslatedTextItem(
    val id: Int,
    val text: String
)

/**
 * Android WebView 向けのネイティブ連携インプレース翻訳エンジン。
 *
 * 【アーキテクチャ】
 * 1. JS: document 及び open Shadow DOM 内のテキストノードを走査し、WeakMap に原文退避 & AndroidBridge に送信
 * 2. Kotlin: Coroutine (Dispatchers.IO) 上で Google 翻訳 API (HTTP POST) を並列実行（CSP 100% 圏外）
 * 3. JS: 翻訳結果の nodeValue を直接置換（リロードなし・スクロール 100% 維持）
 * 4. JS: ワンタッチで WeakMap の原文で即時復帰（リロードなし）
 */
object NativeWebTranslator {

    private const val TAG = "NativeWebTranslator"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jsonHelper = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 画面内のテキストノード（Light DOM + open Shadow DOM）を再帰走査して抽出する JS コード。
     */
    fun buildExtractScript(): String {
        return """
            (function() {
                try {
                    window.__ns_nodes = window.__ns_nodes || new Map();
                    window.__ns_original = window.__ns_original || new WeakMap();
                    window.__ns_next_id = window.__ns_next_id || 0;
                    window.__ns_is_translated = true;

                    var IGNORE_TAGS = {
                        'SCRIPT': true, 'STYLE': true, 'NOSCRIPT': true, 
                        'CODE': true, 'PRE': true, 'TEXTAREA': true, 
                        'INPUT': true, 'SVG': true, 'IFRAME': true
                    };

                    var results = [];

                    function collectTextNodes(root) {
                        if (!root) return;
                        if (root.nodeType === 3) { // Text Node
                            var val = root.nodeValue;
                            if (val && val.trim().length > 0 && !/^\s+$/.test(val)) {
                                var p = root.parentElement;
                                if (p) {
                                    var tag = p.tagName ? p.tagName.toUpperCase() : '';
                                    if (IGNORE_TAGS[tag]) return;
                                    if (p.closest && (p.closest('.skiptranslate') || p.closest('[translate="no"]'))) return;
                                }
                                if (!window.__ns_original.has(root)) {
                                    window.__ns_original.set(root, val);
                                }
                                var id = window.__ns_next_id++;
                                window.__ns_nodes.set(id, root);
                                results.push({ id: id, text: val });
                            }
                            return;
                        }

                        if (root.nodeType === 1) { // Element Node
                            var tag = root.tagName ? root.tagName.toUpperCase() : '';
                            if (IGNORE_TAGS[tag]) return;
                            if (root.classList && (root.classList.contains('skiptranslate') || root.getAttribute('translate') === 'no')) return;

                            // Shadow DOM (open) の探索
                            if (root.shadowRoot) {
                                var sChildren = root.shadowRoot.childNodes;
                                for (var s = 0; s < sChildren.length; s++) {
                                    collectTextNodes(sChildren[s]);
                                }
                            }

                            // 子ノードの探索
                            var children = root.childNodes;
                            for (var c = 0; c < children.length; c++) {
                                collectTextNodes(children[c]);
                            }
                        }
                    }

                    collectTextNodes(document.body);

                    // ネストされた Shadow DOM 要素の追加網羅走査 (Reddit対応)
                    try {
                        var allElements = document.querySelectorAll('*');
                        for (var i = 0; i < allElements.length; i++) {
                            var el = allElements[i];
                            if (el.shadowRoot) {
                                var shadowNodes = el.shadowRoot.childNodes;
                                for (var k = 0; k < shadowNodes.length; k++) {
                                    collectTextNodes(shadowNodes[k]);
                                }
                            }
                        }
                    } catch(e) {}

                    // 動的追加要素（無限スクロール等）の監視を開始
                    startDynamicObserver();

                    if (results.length > 0 && window.AndroidBridge) {
                        try {
                            window.AndroidBridge.onExtractTexts(JSON.stringify(results));
                        } catch(e) {
                            console.error('Bridge call error', e);
                        }
                    }
                    return 'EXTRACTED_' + results.length;
                } catch(e) {
                    console.error('Extract error', e);
                    return 'ERROR: ' + e.message;
                }

                function startDynamicObserver() {
                    if (window.__ns_observer) return;
                    var debounceTimer = null;
                    window.__ns_observer = new MutationObserver(function(mutations) {
                        if (!window.__ns_is_translated) return;
                        clearTimeout(debounceTimer);
                        debounceTimer = setTimeout(function() {
                            if (!window.__ns_is_translated) return;
                            var incrementalResults = [];
                            function scanNew(root) {
                                if (!root) return;
                                if (root.nodeType === 3) {
                                    var val = root.nodeValue;
                                    if (val && val.trim().length > 0 && !window.__ns_original.has(root)) {
                                        var p = root.parentElement;
                                        if (p) {
                                            var tag = p.tagName ? p.tagName.toUpperCase() : '';
                                            if (IGNORE_TAGS[tag]) return;
                                        }
                                        window.__ns_original.set(root, val);
                                        var id = window.__ns_next_id++;
                                        window.__ns_nodes.set(id, root);
                                        incrementalResults.push({ id: id, text: val });
                                    }
                                    return;
                                }
                                if (root.nodeType === 1) {
                                    if (root.shadowRoot) {
                                        var sc = root.shadowRoot.childNodes;
                                        for (var s = 0; s < sc.length; s++) scanNew(sc[s]);
                                    }
                                    var ch = root.childNodes;
                                    for (var c = 0; c < ch.length; c++) scanNew(ch[c]);
                                }
                            }

                            for (var i = 0; i < mutations.length; i++) {
                                var added = mutations[i].addedNodes;
                                if (added) {
                                    for (var j = 0; j < added.length; j++) scanNew(added[j]);
                                }
                            }

                            if (incrementalResults.length > 0 && window.AndroidBridge) {
                                try {
                                    window.AndroidBridge.onExtractTexts(JSON.stringify(incrementalResults));
                                } catch(e) {}
                            }
                        }, 500);
                    });
                    window.__ns_observer.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
    }

    /**
     * 翻訳済みテキストで画面上のテキストノードを直接置換する JS コード。
     */
    fun buildApplyScript(jsonArrayString: String): String {
        val safeJson = jsonArrayString.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function() {
                try {
                    if (!window.__ns_nodes) return 'NO_NODES';
                    var items = JSON.parse('$safeJson');
                    var applied = 0;
                    for (var i = 0; i < items.length; i++) {
                        var item = items[i];
                        var node = window.__ns_nodes.get(item.id);
                        if (node && node.nodeValue !== item.text) {
                            node.nodeValue = item.text;
                            applied++;
                        }
                    }
                    return 'APPLIED_' + applied;
                } catch(e) {
                    console.error('Apply error', e);
                    return 'ERROR: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    /**
     * 退避されていた原文テキストで瞬時に元の言語（英語等）に戻す JS コード。
     */
    fun buildRestoreScript(): String {
        return """
            (function() {
                try {
                    window.__ns_is_translated = false;
                    if (window.__ns_observer) {
                        window.__ns_observer.disconnect();
                        window.__ns_observer = null;
                    }
                    if (window.__ns_nodes && window.__ns_original) {
                        window.__ns_nodes.forEach(function(node) {
                            if (window.__ns_original.has(node)) {
                                node.nodeValue = window.__ns_original.get(node);
                            }
                        });
                    }
                    return 'RESTORED';
                } catch(e) {
                    console.error('Restore error', e);
                    return 'ERROR: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    /**
     * JS から送られた抽出テキストリストを JSON パースし、ネイティブ並列翻訳を実行して WebView に反映する。
     */
    fun translateAndApply(
        scope: CoroutineScope,
        webView: WebView,
        jsonString: String,
        targetLang: String = "ja"
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val items = jsonHelper.decodeFromString<List<ExtractedTextItem>>(jsonString)
                if (items.isEmpty()) return@launch

                Log.d(TAG, "Translating ${items.size} text nodes...")

                // 20件ずつ並列コルーチンで高速翻訳
                val translatedItems = translateParallel(items, targetLang)
                if (translatedItems.isEmpty()) return@launch

                val resultJson = jsonHelper.encodeToString(translatedItems)

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(buildApplyScript(resultJson)) { res ->
                        Log.d(TAG, "Apply result: $res")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateAndApply error", e)
            }
        }
    }

    /**
     * 並列コルーチン（Coroutine async）により、各テキストを高速かつ確実に 1:1 で翻訳。
     */
    suspend fun translateParallel(
        items: List<ExtractedTextItem>,
        targetLang: String = "ja"
    ): List<TranslatedTextItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()

        // 12並列で高速処理
        val chunkSize = 12
        val results = mutableListOf<TranslatedTextItem>()

        for (batch in items.chunked(chunkSize)) {
            val deferreds = batch.map { item ->
                async {
                    val translatedText = fetchTranslationViaPost(item.text, targetLang) ?: item.text
                    val matchedText = matchSpacing(item.text, translatedText)
                    TranslatedTextItem(id = item.id, text = matchedText)
                }
            }
            results.addAll(deferreds.awaitAll())
        }
        results
    }

    private fun matchSpacing(original: String, translated: String): String {
        val leadingSpaces = original.takeWhile { it.isWhitespace() }
        val trailingSpaces = original.takeLastWhile { it.isWhitespace() }
        return leadingSpaces + translated.trim() + trailingSpaces
    }

    /**
     * Google 翻訳 HTTP POST エンドポイント（URL長制限 414 を完全回避 & CSP 100% 圏外）。
     */
    fun fetchTranslationViaPost(text: String, targetLang: String = "ja"): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        // 数字のみや1文字の記号はスキップ
        if (trimmed.all { it.isDigit() || !it.isLetter() }) return text

        return try {
            val postData = "client=gtx&sl=auto&tl=$targetLang&dt=t&q=" + URLEncoder.encode(trimmed, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(postData)
                writer.flush()
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseGoogleTranslateResponse(response)
            } else {
                Log.w(TAG, "Google Translate POST failed with code: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTranslationViaPost error", e)
            null
        }
    }

    /**
     * Google 翻訳の JSON レスポンス `[[["翻訳1", "原文1"], ...], ...]` を解析。
     */
    fun parseGoogleTranslateResponse(jsonString: String): String? {
        return try {
            val jsonElement = jsonHelper.parseToJsonElement(jsonString)
            val rootArray = jsonElement.jsonArray
            val sentencesArray = rootArray.firstOrNull()?.jsonArray ?: return null
            val sb = StringBuilder()
            for (item in sentencesArray) {
                val sentenceArray = item.jsonArray
                val translatedPart = sentenceArray.firstOrNull()?.jsonPrimitive?.contentOrNull
                if (translatedPart != null) {
                    sb.append(translatedPart)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "parseGoogleTranslateResponse error", e)
            null
        }
    }
}
