package com.example.novelscraper

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.example.novelscraper.ui.theme.NovelScraperTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ScrapingViewModel
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ScrapingViewModel::class.java]
        setupSystemUI()
        checkNotificationPermission()

        setContent {
            NovelScraperTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStartScraping = { url -> viewModel.startScraping(url) },
                    onResumeScraping = { url, folder -> viewModel.startScraping(url, folder) },
                    onInspectResult = { sel, _ -> DialogHelper.showInspectResultDialog(this, sel) },
                    onLaunchAnalysisTool = { view -> launchAnalysisTool(view) },
                    onInjectInspector = { view -> injectInspector(view) },
                    onNavigate = { url, view -> performNavigation(url, view) },
                    onShowAddFavorite = { title, url ->
                        DialogHelper.showAddFavoriteDialog(this, title) { name ->
                            viewModel.saveFavorite(name, url)
                        }
                    },
                    onShowSavePreset = {
                        val state = viewModel.uiState.value
                        DialogHelper.showSavePresetDialog(
                            this, state.currentPresetName, state.currentUrl, viewModel.presets.value
                        ) { name -> viewModel.savePreset(name, state.currentConfig) }
                    },
                    onTestRun = { view -> performTestRun(view) }
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
        window.statusBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onResume() {
        super.onResume()
        WebView.setWebContentsDebuggingEnabled(false)
    }

    private fun performNavigation(input: String, view: WebView) {
        if (input.isEmpty()) return

        // 異常に長い入力に対する保護
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
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, !viewModel.uiState.value.blockImages)
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
                    val chap = ChapterNumberExtractor.extractForDisplay(data.chapter, config.chapter, view.url ?: "")
                    DialogHelper.showTestResultDialog(this, data, chap)
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

    inner class NovelScraperBridge {
        @JavascriptInterface
        fun onInspectResult(selector: String) {
            mainHandler.post {
                DialogHelper.showInspectResultDialog(this@MainActivity, selector)
            }
        }
    }

    @android.annotation.SuppressLint("JavascriptInterface")
    private fun injectInspector(view: WebView?) {
        view?.let { v ->
            v.addJavascriptInterface(NovelScraperBridge(), "AndroidBridge")
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorScript(), null)
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