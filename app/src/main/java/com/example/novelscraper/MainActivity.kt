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
                        onNavigate = { url, view -> performNavigation(url, view) },
                        onRequestExclude = { selector -> handleExcludeRequest(selector) },
                        onTestRun = { view -> performTestRun(view) },
                        onToggleLiveTranslate = { view -> toggleLiveTranslation(view) }
                    )
                )
            }
        }
        observeViewModel()
        if (savedInstanceState == null) {
            handleIntent(intent)
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
        if (intent.action != Intent.ACTION_SEND) return
        // Manifestは text/plain のみ宣言。受信側は text/* と type==null（EXTRA_TEXT付きの変則共有）を
        // 寛容に受け付ける。判定は「データ有無」が主、MIMEは補助（公式「想定外データが来る前提で検証せよ」に準拠）。
        val type = intent.type
        if (type != null && !type.startsWith("text/")) return
        try {
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
            candidates += extraText
            candidates += htmlText
            candidates += clipTexts
            candidates += subject
            val extractedUrl = UrlExtractor.extractUrlFromCandidates(*candidates.toTypedArray())
            if (extractedUrl != null) {
                // setCurrentUrlがinputUrlも同時更新するためsetInputUrlの重複呼び出しはしない。
                // 同一URL再共有は状態更新せずToastのみで二重ロードと表示矛盾を防ぐ。
                val currentNormalized = viewModel.uiState.value.currentUrl.trimEnd('/')
                if (currentNormalized == extractedUrl.trimEnd('/')) {
                    viewModel.closePanels()
                    Toast.makeText(this, "そのURLは既に開いています", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.closePanels()
                    viewModel.setCurrentUrl(extractedUrl)
                    Toast.makeText(this, "共有されたURLを開きます", Toast.LENGTH_SHORT).show()
                }
            } else {
                android.util.Log.w(TAG, "handleIntent: no URL found in shared content (type=$type)")
                Toast.makeText(this, "共有内容にURLが見つかりませんでした", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleIntent: failed to handle shared intent", e)
            Toast.makeText(this, "共有の受け取りに失敗しました", Toast.LENGTH_SHORT).show()
        } finally {
            // 消費済み化: 回転・再生成・タスク復帰での再発火を防ぐ（actionをMAINに戻し共有extraを除去）。
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.removeExtra(Intent.EXTRA_SUBJECT)
            intent.removeExtra(EXTRA_HTML_TEXT)
            try {
                intent.clipData = null
            } catch (_: Exception) {
                android.util.Log.w(TAG, "handleIntent: failed to clear clipData")
            }
            intent.action = Intent.ACTION_MAIN
        }
    }

    private fun setupSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    override fun onResume() {
        super.onResume()
        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun performNavigation(input: String, view: WebView) {
        if (input.isEmpty()) return

        if (input.length > 2000) {
            Toast.makeText(this, "URLまたは検索クエリが長すぎます。無効なデータです。", Toast.LENGTH_LONG).show()
            return
        }

        val target = if (android.util.Patterns.WEB_URL.matcher(input).matches() || android.webkit.URLUtil.isValidUrl(input)) {
            if (!input.lowercase().startsWith("http")) "https://$input" else input
        } else {
            "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
        }
        view.loadUrl(target)
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
                    when (event) {
                        is UiEvent.ShowToast -> Toast.makeText(
                            this@MainActivity,
                            event.message,
                            if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                        ).show()
                        // 発行元なし（予約型）。単一パイプライン維持のため状態更新経由に寄せる。
                        is UiEvent.NavigateToUrl -> viewModel.setCurrentUrl(event.url)
                    }
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
