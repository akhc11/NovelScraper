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
import com.example.novelscraper.ui.MainScreen
import com.example.novelscraper.ui.MainScreenCallbacks
import com.example.novelscraper.ui.theme.NovelScraperTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * WebView との通信用ブリッジ。
 * Activity への暗黙参照によるメモリリークを防止するため、独立クラスとして定義。
 */
class NovelScraperBridge(
    private val onInspect: (String) -> Unit,
    private val onApply: (String, String) -> Unit,
    private val onRemove: (String) -> Unit
) {
    @JavascriptInterface
    fun onInspectResult(selector: String) {
        onInspect(selector)
    }

    @JavascriptInterface
    fun onApplyCandidate(target: String, selector: String) {
        onApply(target, selector)
    }

    @JavascriptInterface
    fun onRemoveExclude(selector: String) {
        onRemove(selector)
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ScrapingViewModel
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mainWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ScrapingViewModel::class.java]
        setupSystemUI()
        checkNotificationPermission()
        WebView.setWebContentsDebuggingEnabled(true)

        setContent {
            NovelScraperTheme {
                MainScreen(
                    viewModel = viewModel,
                    callbacks = MainScreenCallbacks(
                        onStartScraping = { url -> viewModel.startScraping(url) },
                        onResumeScraping = { url, folder -> viewModel.startScraping(url, folder) },
                        onLaunchAnalysisTool = { view -> launchAnalysisTool(view) },
                        onSetupWebView = { view -> setupWebView(view) },
                        onInjectInspector = { view -> injectInspector(view) },
                        onRemoveInspector = { view -> removeInspector(view) },
                        onNavigate = { url, view -> performNavigation(url, view) },
                        onRequestExclude = { selector -> handleExcludeRequest(selector) },
                        onTestRun = { view -> performTestRun(view) }
                    )
                )
            }
        }
        observeViewModel()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && (intent.type?.startsWith("text/") == true || intent.type == "text/plain")) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            val extractedUrl = UrlExtractor.extractUrl(sharedText)
            if (extractedUrl != null) {
                viewModel.closePanels()
                viewModel.setInputUrl(extractedUrl)
                viewModel.setCurrentUrl(extractedUrl)
                Toast.makeText(this, "共有されたURLを開きます", Toast.LENGTH_SHORT).show()
            }
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
            if (!input.startsWith("http")) "https://$input" else input
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
                        presets["初期設定(小説家になろう)"]?.let {
                            viewModel.applyPresetState("初期設定(小説家になろう)", it)
                        }
                    }
                }
            }
        }
    }

    private fun launchAnalysisTool(view: WebView) {
        view.evaluateJavascript(ScrapingScriptBuilder.buildErudaScript(), null)
    }

    @android.annotation.SuppressLint("JavascriptInterface")
    private fun setupWebView(view: WebView) {
        mainWebView = view
        val bridge = NovelScraperBridge(
            onInspect = { selector ->
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        viewModel.showInspectElementDialog(selector)
                    }
                }
            },
            onApply = { target, selector ->
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        try {
                            val field = SelectorField.valueOf(target.uppercase())
                            viewModel.applySelectorToConfig(field, selector)
                            Toast.makeText(this@MainActivity, "${field.displayName}に反映しました", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "適用失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onRemove = { selector ->
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        viewModel.removeExcludeSelector(selector)
                    }
                }
            }
        )
        view.addJavascriptInterface(bridge, "AndroidBridge")
    }

    private fun injectInspector(view: WebView?) {
        view?.let { v ->
            mainWebView = v
            val config = viewModel.uiState.value.currentConfig
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorScript(config), null)
        }
    }

    private fun removeInspector(view: WebView?) {
        view?.let { v ->
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorStopScript(), null)
        }
    }

    /**
     * テスト結果パネルの行タップ → 除外候補をプローブして状態に反映（WebView注入不要）。
     * 候補カードの適用/自動再テストは Compose 側 (MainScreen) で行う。
     */
    private fun handleExcludeRequest(selector: String) {
        val view = mainWebView ?: return
        view.evaluateJavascript(ScrapingScriptBuilder.buildCandidateProbeScript(selector)) { res ->
            if (res != null && res != "null") {
                try {
                    val raw = Json.decodeFromString<String>(res)
                    val items = Json.decodeFromString<List<ExcludeCandidate>>(raw)
                    if (items.isNotEmpty()) {
                        viewModel.setExcludeCandidates(ExcludeCandidatesState(baseSelector = selector, items = items))
                    } else {
                        Toast.makeText(this, "除外候補が見つかりません", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "候補取得失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
}
