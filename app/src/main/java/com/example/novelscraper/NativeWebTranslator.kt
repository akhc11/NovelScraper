package com.example.novelscraper

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
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
 * 【特徴】
 * 1. リロード一切なし・スクロール位置 100% 維持（文字の nodeValue のみ直接置換）
 * 2. CSP (script-src / connect-src) 100% バイパス（ネイティブ通信経由）
 * 3. Shadow DOM (open mode) 再帰走査により Reddit / Web Components サイトに完全対応
 * 4. 原文への瞬時復帰（WeakMap キャッシュによるリロードなし復帰）
 * 5. 動的 DOM / 無限スクロールの自動追従翻訳 (MutationObserver 連携)
 */
object NativeWebTranslator {

    private const val TAG = "NativeWebTranslator"
    private val jsonHelper = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 画面内のテキストノード（Light DOM + open Shadow DOM）を再帰走査して抽出する JS コードを生成。
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

                    function walk(root) {
                        if (!root) return;

                        // 通常のテキストノード
                        if (root.nodeType === 3) {
                            var rawText = root.nodeValue;
                            if (rawText && rawText.trim().length > 0 && !/^\s+$/.test(rawText)) {
                                var parent = root.parentElement;
                                if (parent) {
                                    var tagName = parent.tagName ? parent.tagName.toUpperCase() : '';
                                    if (IGNORE_TAGS[tagName]) return;
                                    if (parent.closest && (parent.closest('.skiptranslate') || parent.closest('[translate="no"]'))) return;
                                }

                                if (!window.__ns_original.has(root)) {
                                    window.__ns_original.set(root, rawText);
                                }

                                var id = window.__ns_next_id++;
                                window.__ns_nodes.set(id, root);
                                results.push({ id: id, text: rawText });
                            }
                            return;
                        }

                        // Element ノード
                        if (root.nodeType === 1) {
                            var tag = root.tagName ? root.tagName.toUpperCase() : '';
                            if (IGNORE_TAGS[tag]) return;
                            if (root.classList && (root.classList.contains('skiptranslate') || root.getAttribute('translate') === 'no')) return;

                            // Shadow DOM (open mode) の探索
                            if (root.shadowRoot) {
                                var shadowChildren = root.shadowRoot.childNodes;
                                for (var s = 0; s < shadowChildren.length; s++) {
                                    walk(shadowChildren[s]);
                                }
                            }

                            // 通常の子ノードの探索
                            var children = root.childNodes;
                            for (var i = 0; i < children.length; i++) {
                                walk(children[i]);
                            }
                        }
                    }

                    walk(document.body);

                    // 動的追加要素（無限スクロール等）の監視を開始
                    startDynamicObserver();

                    if (results.length > 0 && window.AndroidBridge && window.AndroidBridge.onExtractTexts) {
                        window.AndroidBridge.onExtractTexts(JSON.stringify(results));
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
                        var newNodes = [];
                        for (var i = 0; i < mutations.length; i++) {
                            var added = mutations[i].addedNodes;
                            if (!added) continue;
                            for (var j = 0; j < added.length; j++) {
                                newNodes.push(added[j]);
                            }
                        }
                        if (newNodes.length > 0) {
                            clearTimeout(debounceTimer);
                            debounceTimer = setTimeout(function() {
                                if (!window.__ns_is_translated) return;
                                var incrementalResults = [];
                                function walkIncremental(root) {
                                    if (!root) return;
                                    if (root.nodeType === 3) {
                                        var raw = root.nodeValue;
                                        if (raw && raw.trim().length > 0 && !window.__ns_original.has(root)) {
                                            var parent = root.parentElement;
                                            if (parent) {
                                                var tagName = parent.tagName ? parent.tagName.toUpperCase() : '';
                                                if (IGNORE_TAGS[tagName]) return;
                                            }
                                            window.__ns_original.set(root, raw);
                                            var id = window.__ns_next_id++;
                                            window.__ns_nodes.set(id, root);
                                            incrementalResults.push({ id: id, text: raw });
                                        }
                                        return;
                                    }
                                    if (root.nodeType === 1) {
                                        if (root.shadowRoot) {
                                            var sc = root.shadowRoot.childNodes;
                                            for (var s = 0; s < sc.length; s++) walkIncremental(sc[s]);
                                        }
                                        var ch = root.childNodes;
                                        for (var k = 0; k < ch.length; k++) walkIncremental(ch[k]);
                                    }
                                }
                                for (var n = 0; n < newNodes.length; n++) {
                                    walkIncremental(newNodes[n]);
                                }
                                if (incrementalResults.length > 0 && window.AndroidBridge && window.AndroidBridge.onExtractTexts) {
                                    window.AndroidBridge.onExtractTexts(JSON.stringify(incrementalResults));
                                }
                            }, 400);
                        }
                    });
                    window.__ns_observer.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
    }

    /**
     * 翻訳済みテキストで画面上のテキストノードを直接置換する JS コードを生成。
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
     * 退避されていた原文テキストで瞬時に元の言語（英語等）に戻す JS コードを生成。
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
     * JS から送られた抽出テキストリストを JSON パースし、ネイティブ翻訳を実行して WebView に反映する。
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

                val translatedItems = translateBatch(items, targetLang)
                if (translatedItems.isEmpty()) return@launch

                val resultJson = jsonHelper.encodeToString(translatedItems)

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(buildApplyScript(resultJson), null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateAndApply error", e)
            }
        }
    }

    /**
     * テキストリストをバッチ化して Google 翻訳 API（無料エンドポイント）でネイティブ高速翻訳。
     */
    suspend fun translateBatch(
        items: List<ExtractedTextItem>,
        targetLang: String = "ja"
    ): List<TranslatedTextItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<TranslatedTextItem>()
        val batchSize = 25

        for (chunk in items.chunked(batchSize)) {
            val combinedText = chunk.joinToString("\n\n===__NS_SEP__===\n\n") { it.text }
            val translatedCombined = fetchTranslationFromGoogle(combinedText, targetLang)

            if (translatedCombined != null) {
                val splitResults = translatedCombined.split(Regex("\n*===__NS_SEP__===\n*"))
                for (i in chunk.indices) {
                    val originalItem = chunk[i]
                    val translatedText = if (i < splitResults.size) splitResults[i].trim() else originalItem.text
                    val finalResult = matchSpacing(originalItem.text, translatedText)
                    results.add(TranslatedTextItem(id = originalItem.id, text = finalResult))
                }
            } else {
                for (item in chunk) {
                    val singleResult = fetchTranslationFromGoogle(item.text, targetLang) ?: item.text
                    results.add(TranslatedTextItem(id = item.id, text = singleResult))
                }
            }
        }
        results
    }

    private fun matchSpacing(original: String, translated: String): String {
        val leadingSpaces = original.takeWhile { it.isWhitespace() }
        val trailingSpaces = original.takeLastWhile { it.isWhitespace() }
        return leadingSpaces + translated.trim() + trailingSpaces
    }

    /**
     * Google 翻訳 HTTP エンドポイントからネイティブに翻訳を取得（CSP 100% 圏外）。
     */
    fun fetchTranslationFromGoogle(text: String, targetLang: String = "ja"): String? {
        if (text.isBlank()) return text
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseGoogleTranslateResponse(response)
            } else {
                Log.w(TAG, "Google Translate API response code: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTranslationFromGoogle error", e)
            null
        }
    }

    /**
     * Google 翻訳の JSON レスポンス `[[["翻訳1", "原文1"], ["翻訳2", "原文2"]], ...]` を解析。
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
