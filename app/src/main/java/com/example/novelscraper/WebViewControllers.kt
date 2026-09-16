package com.example.novelscraper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.util.Log
import android.webkit.WebView
import com.example.novelscraper.scraper.ScraperConfig
import com.example.novelscraper.scraper.ScrapingScriptBuilder
import com.example.novelscraper.scraper.SelectorField
import com.example.novelscraper.translation.web.LiveTranslateScriptBuilder
import kotlinx.serialization.json.Json

/**
 * MainActivityが保持する現行WebViewへの単一参照。
 * onDestroyで cleared する。Controller群はActivity参照を持たず、このHolder経由でのみ触れる。
 */
class WebViewHolder {
    var current: WebView? = null
    fun clear() {
        current = null
    }
}

/** インスペクター注入・除去・解析ツール起動を担当（再注入禁止・差分同期の責務はJS側と協調）。 */
class InspectorController(
    private val holder: WebViewHolder,
    private val getConfig: () -> ScraperConfig
) {
    fun track(view: WebView) {
        holder.current = view
    }

    fun inject(view: WebView?) {
        view?.let { v ->
            holder.current = v
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorScript(getConfig()), null)
        }
    }

    fun remove(view: WebView?) {
        view?.let { v ->
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorStopScript(), null)
        }
    }

    fun launchAnalysisTool(view: WebView) {
        view.evaluateJavascript(ScrapingScriptBuilder.buildErudaScript(), null)
    }
}

/** ライブ翻訳トグルを担当（500msデバウンス維持）。 */
class LiveTranslateController(
    private val holder: WebViewHolder,
    private val toast: (String) -> Unit
) {
    private var lastToggleTime = 0L

    fun toggle(view: WebView?) {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < 500L) {
            Log.d(TAG, "toggleLiveTranslation: Debounced rapid toggle click")
            return
        }
        lastToggleTime = now

        val v = view ?: holder.current
        if (v == null) {
            Log.e(TAG, "toggleLiveTranslation: WebView is null")
            toast("WebViewの初期化待ちです")
            return
        }
        Log.d(TAG, "toggleLiveTranslation: Evaluating script on WebView url=${v.url}")
        val js = LiveTranslateScriptBuilder.buildToggleLiveTranslateScript()
        v.evaluateJavascript(js) { res ->
            Log.d(TAG, "toggleLiveTranslation evaluateJavascript result: $res")
        }
    }

    companion object {
        private const val TAG = "LiveTranslateController"
    }
}

/** 除外候補プローブを担当（破棄後Activityでの状態更新を防ぐ）。 */
class ProbeController(
    private val holder: WebViewHolder,
    private val viewModel: ScrapingViewModel,
    private val isAlive: () -> Boolean,
    private val toast: (String) -> Unit
) {
    fun handleExcludeRequest(selector: String) {
        if (!isAlive()) return
        val view = holder.current ?: return
        view.evaluateJavascript(ScrapingScriptBuilder.buildCandidateProbeScript(selector)) { res ->
            if (!isAlive()) return@evaluateJavascript
            if (res != null && res != "null") {
                try {
                    val raw = Json.decodeFromString<String>(res)
                    val items = Json.decodeFromString<List<ExcludeCandidate>>(raw)
                    if (items.isNotEmpty()) {
                        viewModel.setExcludeCandidates(ExcludeCandidatesState(baseSelector = selector, items = items))
                    } else {
                        toast("除外候補が見つかりません")
                    }
                } catch (e: Exception) {
                    toast("候補取得失敗: ${e.message}")
                }
            }
        }
    }
}

/**
 * WebView↔Activityブリッジの組立てを担当。
 * NovelScraperBridge自体はActivityへの暗黙参照を持たない独立クラスのまま維持する。
 */
class ScraperBridgeFactory(
    private val viewModel: ScrapingViewModel,
    private val mainHandler: Handler,
    private val context: Context,
    private val isAlive: () -> Boolean,
    private val toast: (String) -> Unit
) {
    fun attach(view: WebView, holder: WebViewHolder) {
        holder.current = view
        val bridge = NovelScraperBridge(
            onApply = { target, selector ->
                mainHandler.post {
                    if (isAlive()) {
                        try {
                            val field = when (target.lowercase().trim()) {
                                "body" -> SelectorField.BODY
                                "title" -> SelectorField.TITLE
                                "next" -> SelectorField.NEXT
                                "folder" -> SelectorField.FOLDER
                                "folder_link", "folderlink" -> SelectorField.FOLDER_LINK
                                "chapter" -> SelectorField.CHAPTER
                                "exclude" -> SelectorField.EXCLUDE
                                else -> SelectorField.valueOf(target.uppercase().trim())
                            }
                            viewModel.applySelectorToConfig(field, selector)
                            toast("${field.displayName}に反映しました")
                        } catch (e: Exception) {
                            toast("適用失敗: ${e.message}")
                        }
                    }
                }
            },
            onCopy = { selector ->
                mainHandler.post {
                    if (isAlive()) {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("CSS Selector", selector)
                            clipboard.setPrimaryClip(clip)
                            toast("セレクタをコピーしました")
                        } catch (e: Exception) {
                            toast("コピー失敗: ${e.message}")
                        }
                    }
                }
            },
            onRemove = { selector ->
                mainHandler.post {
                    if (isAlive()) {
                        viewModel.removeExcludeSelector(selector)
                    }
                }
            },
            onStatusUpdate = { status ->
                mainHandler.post {
                    if (isAlive()) {
                        Log.d(TAG, "onLiveTranslateStatus: $status")
                        viewModel.handleLiveTranslateStatus(status)
                        if (status == "SUCCESS") {
                            toast("ページを翻訳しました")
                        } else if (status == "RESTORED") {
                            toast("原文に復元しました")
                        } else if (status.startsWith("ERROR")) {
                            toast("翻訳エラー: $status")
                        }
                    }
                }
            }
        )
        view.addJavascriptInterface(bridge, "AndroidBridge")
    }

    companion object {
        private const val TAG = "ScraperBridgeFactory"
    }
}
