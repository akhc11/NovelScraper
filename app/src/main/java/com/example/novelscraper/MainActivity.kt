package com.example.novelscraper

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.novelscraper.scraper.*
import com.example.novelscraper.translation.v2.service.TranslationDiagnostics
import com.example.novelscraper.translation.web.*
import com.example.novelscraper.ui.MainScreen
import com.example.novelscraper.ui.MainScreenCallbacks
import com.example.novelscraper.ui.theme.NovelScraperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * WebView との通信用ブリッジ。
 * Activity への暗黙参照によるメモリリークを防止するため、独立クラスとして定義。
 */
class NovelScraperBridge(
    private val onApply: (String, String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onRemove: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {

    @JavascriptInterface
    fun onApplyCandidate(target: String, selector: String) {
        onApply(target, selector)
    }

    @JavascriptInterface
    fun onCopySelector(selector: String) {
        onCopy(selector)
    }

    @JavascriptInterface
    fun onRemoveExclude(selector: String) {
        onRemove(selector)
    }

    @JavascriptInterface
    fun onLiveTranslateStatus(status: String) {
        onStatusUpdate(status)
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // androidx.core.content.IntentCompat.EXTRA_HTML_TEXT と同値。新規依存を追加せず参照するための定数。
        private const val EXTRA_HTML_TEXT = "android.intent.extra.HTML_TEXT"
        private const val KEY_SAVED_URL = "novelscraper:last_url"
    }

    private lateinit var viewModel: ScrapingViewModel
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewHolder = WebViewHolder()
    private lateinit var inspectorController: InspectorController
    private lateinit var liveTranslateController: LiveTranslateController
    private lateinit var probeController: ProbeController
    private lateinit var bridgeFactory: ScraperBridgeFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 技術的根拠1行：プロセス死は通知なしのため、起動時に前回実行マーカーと突合して中断検知する（重い読込はIOに寄せる）。
        lifecycleScope.launch(Dispatchers.IO) {
            val interrupted = TranslationDiagnostics.checkInterrupted(this@MainActivity)
            if (interrupted != null) {
                android.util.Log.w(TAG, "previous translation run was interrupted (likely OS kill): $interrupted")
            }
        }
        viewModel = ViewModelProvider(this)[ScrapingViewModel::class.java]
        // プロセス死からの復帰時は表示URLだけ復元する（ページ実体はWebViewのsaveState復元が担う）。
        // 技術的根拠1行：ViewModelはプロセス死で空に戻るため、地址欄が空白のまま残る不整合を防ぐ。共有intentがあれば後続handleIntentが上書きする。
        savedInstanceState?.getString(KEY_SAVED_URL)?.takeIf { it.isNotEmpty() }?.let {
            viewModel.setCurrentUrl(it)
        }
        val shortToast: (String) -> Unit =
            { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        val isAlive: () -> Boolean = { !isFinishing && !isDestroyed }
        inspectorController = InspectorController(webViewHolder) { viewModel.uiState.value.currentConfig }
        liveTranslateController = LiveTranslateController(webViewHolder, shortToast)
        probeController = ProbeController(webViewHolder, viewModel, isAlive, shortToast)
        bridgeFactory = ScraperBridgeFactory(
            viewModel, mainHandler, this, isAlive, shortToast,
            // 単一フロー：原文復元でInspector DOMが消えるため探査中のみ再注入する。
            // 技術的根拠1行：innerHTML復元は注入ノードも消すがoverlay状態は残るため、状態駆動で修復する。
            onRestored = {
                if (isAlive() && viewModel.uiState.value.overlay is Overlay.InspectMode) {
                    inspectorController.inject(webViewHolder.current)
                }
            }
        )
        setupSystemUI()
        checkNotificationPermission()
        WebView.setWebContentsDebuggingEnabled(true)

        setContent {
            NovelScraperTheme {
                MainScreen(
                    viewModel = viewModel,
                    callbacks = MainScreenCallbacks(
                        onLaunchAnalysisTool = { view -> launchAnalysisTool(view) },
                        onSetupWebView = { view -> setupWebView(view) },
                        onInjectInspector = { view -> injectInspector(view) },
                        onRemoveInspector = { view -> removeInspector(view) },
                        onRequestExclude = { selector -> handleExcludeRequest(selector) },
                        onTestRun = { view -> performTestRun(view) },
                        onToggleLiveTranslate = { view -> toggleLiveTranslation(view) }
                    )
                )
            }
        }
        observeViewModel()
        // savedInstanceStateの有無に関わらず毎回処理する。
        // 技術的根拠1行：回転再生成では同一intent再配送・プロセス死後の再生成ではonCreate後にonNewIntentが来るが、消費済み化（action=MAIN＋extra除去）済みなら再処理は無害なため、ガードで落とすより毎回処理が取りこぼさない。
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putString(KEY_SAVED_URL, viewModel.uiState.value.currentUrl)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        webViewHolder.clear()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        // 受信対象は共有(SEND)・リンク開放(VIEW)・選択テキスト渡し(PROCESS_TEXT)のみ。それ以外(MAIN等)は何もしない。
        // 技術的根拠1行：Chromeの共有はSENDだが「他のアプリで開く」系はVIEW+data、他アプリのテキスト選択メニューはPROCESS_TEXTで届くため、SENDのみ処理すると受信漏れで不動になる（公式intent-filter指針）。
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW && action != Intent.ACTION_PROCESS_TEXT) return
        try {
            // SEND/PROCESS_TEXT/VIEW共通の抽出。VIEWはdata先行、それ以外はEXTRA_TEXT先行で候補順にする。
            val extractedUrl = extractSharedUrl(intent, dataFirst = (action == Intent.ACTION_VIEW))
            if (extractedUrl != null) {
                // 遷移は one-shot コマンドでWebView実体へ一発配送する。状態一致での抑止はしない
                // （状態と実表示の不整合時に再共有で修復できなくなるため）。重複loadUrlの抑止は
                // 収集側でWebView実URL比較により行う。
                viewModel.closePanels()
                viewModel.navigateTo(extractedUrl)
                Toast.makeText(this, "共有されたURLを開きます", Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.w(TAG, "handleIntent: no URL found in shared content (action=$action type=${intent.type})")
                Toast.makeText(this, "共有内容にURLが見つかりませんでした", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleIntent: failed to handle shared intent", e)
            Toast.makeText(this, "共有の受け取りに失敗しました", Toast.LENGTH_SHORT).show()
        } finally {
            // 消費済み化: 回転・再生成・タスク復帰での再発火を防ぐ（actionをMAINに戻し共有extraとdataを除去）。
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.removeExtra(Intent.EXTRA_SUBJECT)
            intent.removeExtra(EXTRA_HTML_TEXT)
            try {
                intent.clipData = null
            } catch (_: Exception) {
                android.util.Log.w(TAG, "handleIntent: failed to clear clipData")
            }
            try {
                intent.data = null
            } catch (_: Exception) {
                android.util.Log.w(TAG, "handleIntent: failed to clear data")
            }
            intent.action = Intent.ACTION_MAIN
        }
    }

    /**
     * 共有intentからURLを抽出する唯一の入口（SEND/PROCESS_TEXT/VIEW共用）。
     * Manifestは text/plain のみ宣言。受信側は text系MIME と type==null（EXTRA_TEXT付きの変則共有）を
     * 寛容に受け付ける。判定は「データ有無」が主、MIMEは補助（公式「想定外データが来る前提で検証せよ」に準拠）。
     */
    private fun extractSharedUrl(intent: Intent, dataFirst: Boolean): String? {
        // VIEWはURI起点のためMIME判定をしない（非テキストtype付きでもdataのURLを拾う）。
        // SEND系のみ text/* と type==null を寛容に受け付ける。
        if (!dataFirst) {
            val type = intent.type
            if (type != null && !type.startsWith("text/")) return null
        }
        val dataString = try {
            intent.dataString ?: intent.data?.toString()
        } catch (_: Exception) {
            null
        }
        val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val htmlText = intent.getCharSequenceExtra(EXTRA_HTML_TEXT)?.toString()
            ?: intent.getStringExtra(EXTRA_HTML_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val clipTexts = mutableListOf<String?>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                clipTexts += item.text?.toString()
                clipTexts += item.htmlText?.toString()
            }
        }
        val candidates = mutableListOf<String?>()
        if (dataFirst) candidates += dataString
        candidates += extraText
        candidates += htmlText
        candidates += clipTexts
        candidates += subject
        if (!dataFirst) candidates += dataString
        return UrlExtractor.extractUrlFromCandidates(*candidates.toTypedArray())
    }

    private fun setupSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    override fun onResume() {
        super.onResume()
        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun performTestRun(view: WebView) {
        val config = viewModel.uiState.value.currentConfig
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, !viewModel.uiState.value.blockImages, true)
        view.evaluateJavascript(jsCode) { res ->
            if (res != null && res != "null") {
                try {
                    val rawResult = Json.decodeFromString<String>(res)
                    if (rawResult == "CF_DETECTED") {
                        Toast.makeText(this, "Cloudflare検知", Toast.LENGTH_SHORT).show()
                        return@evaluateJavascript
                    }
                    if (rawResult.startsWith("JS_ERROR")) {
                        Toast.makeText(this, rawResult, Toast.LENGTH_LONG).show()
                        return@evaluateJavascript
                    }

                    val data = Json.decodeFromString<ScrapingResult>(rawResult)
                    val chapDisplay = ChapterNumberExtractor.extractForDisplay(data.chapter, config.chapter, view.url ?: "")
                    viewModel.setTestResult(data.copy(chapterDisplay = chapDisplay))
                } catch (e: Exception) {
                    Toast.makeText(this, "解析失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.presets.collect { presets ->
                    if (viewModel.uiState.value.currentPresetName.isEmpty() && presets.containsKey("初期設定(小説家になろう)")) {
                        // 既に手動で設定が入力されている場合は初期設定で上書きしない
                        if (viewModel.uiState.value.currentConfig == ScraperConfig()) {
                            presets["初期設定(小説家になろう)"]?.let {
                                viewModel.applyPresetState("初期設定(小説家になろう)", it)
                            }
                        }
                    }
                }
            }
        }
        // UiEventの一元収集点（表示は従来通りToast。文言・長さ不変）。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.eventBus.events.collect { event ->
                    if (event is UiEvent.ShowToast) {
                        Toast.makeText(
                            this@MainActivity,
                            event.message,
                            if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        // 外部遷移コマンドの単一収集点：WebView実体へ一発loadUrlする。
        // 技術的根拠1行：loadUrlは現行ロードをcancelするため(AOSP)、状態変化の度に撃つとリンクタップ・リダイレクトが壊れる。外部要求のみここで撃ち、ページ内遷移はWebViewに任せる。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationCommands.collect { url ->
                    val view = webViewHolder.current
                    if (view != null) {
                        // 実表示との比較で重複のみ抑止（状態比較では不整合時に修復不能になるため使わない）。
                        val current = try {
                            view.url?.trimEnd('/') ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        if (current != url.trimEnd('/')) {
                            try {
                                view.loadUrl(url)
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "navigation command failed: $url", e)
                            }
                        }
                    }
                    // WebView未生成時（冷起動直後）は何もしない。
                    // navigateToがcurrentUrl表示を同期更新済みのため、生成時の初期ロードで同一URLを開く。
                    // なお冷起動では生成時ロードと本コマンドが同一URLに2度loadUrlし得るが、後発が先行を取り消すだけで可視上の多重表示・循環にはならないため、抑止機構は持たない（複雑化の方が害）。
                }
            }
        }
    }

    private fun launchAnalysisTool(view: WebView) {
        inspectorController.launchAnalysisTool(view)
    }

    @android.annotation.SuppressLint("JavascriptInterface")
    private fun setupWebView(view: WebView) {
        bridgeFactory.attach(view, webViewHolder)
    }

    private fun toggleLiveTranslation(view: WebView?) {
        liveTranslateController.toggle(view)
    }

    private fun injectInspector(view: WebView?) {
        inspectorController.inject(view)
    }

    private fun removeInspector(view: WebView?) {
        inspectorController.remove(view)
    }

    /**
     * テスト結果パネルの行タップ → 除外候補をプローブして状態に反映（WebView注入不要）。
     * 候補カードの適用/自動再テストは Compose 側 (MainScreen) で行う。
     */
    private fun handleExcludeRequest(selector: String) {
        probeController.handleExcludeRequest(selector)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
}
