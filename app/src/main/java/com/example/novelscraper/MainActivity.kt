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
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.novelscraper.ui.MainScreen
import com.example.novelscraper.ui.theme.NovelScraperTheme
import kotlinx.coroutines.flow.combine
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
    }

    private fun setupSystemUI() {
        window.statusBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * バックグラウンドから復帰した際にWebViewのJSタイマーを再開する。
     * OSがバックグラウンドでWebViewのタイマーを凍結しても、
     * フォアグラウンド復帰時に即座に再開される。
     */
    override fun onResume() {
        super.onResume()
        WebView.setWebContentsDebuggingEnabled(false)
        // 全WebViewインスタンスのJSタイマーを再開
        try {
            val resumeMethod = WebView::class.java.getMethod("resumeTimers")
            val tempView = WebView(applicationContext)
            resumeMethod.invoke(tempView)
            tempView.destroy()
        } catch (e: Exception) {
            // resumeTimers失敗時は無視
        }
    }

    private fun performNavigation(input: String, view: WebView) {
        if (input.isEmpty()) return

        // 異常に長い入力（過去のバグによるスクリプトデータの混入など）に対する保護
        if (input.length > 2000) {
            Toast.makeText(this, "URL\u307e\u305f\u306f\u691c\u7d22\u30af\u30a8\u30ea\u304c\u9577\u3059\u304e\u307e\u3059\u3002\u7121\u52b9\u306a\u30c7\u30fc\u30bf\u3067\u3059\u3002", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, "Cloudflare\u691c\u77e5", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "\u89e3\u6790\u5931\u6557", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.presets.collect { presets ->
                    if (viewModel.uiState.value.currentPresetName.isEmpty() && presets.containsKey("\u521d\u671f\u8a2d\u5b9a(\u5c0f\u8aac\u5bb6\u306b\u306a\u308d\u3046)")) {
                        presets["\u521d\u671f\u8a2d\u5b9a(\u5c0f\u8aac\u5bb6\u306b\u306a\u308d\u3046)"]?.let {
                            viewModel.applyPresetState("\u521d\u671f\u8a2d\u5b9a(\u5c0f\u8aac\u5bb6\u306b\u306a\u308d\u3046)", it)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.activeTasks, viewModel.uiState) { tasks, uiState ->
                    Pair(tasks, uiState)
                }.collect { (tasks, uiState) ->
                    updateServiceStatus(tasks.size, uiState.isTranslating, uiState)
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

    private fun updateServiceStatus(scrapingTaskCount: Int, isTranslating: Boolean, uiState: MainUiState) {
        mainHandler.post {
            val intent = Intent(this, ScraperService::class.java)
            if (scrapingTaskCount > 0 || isTranslating) {
                intent.action = ScraperService.ACTION_UPDATE_STATUS
                val msg = when {
                    scrapingTaskCount > 0 && isTranslating ->
                        "\u30b9\u30af\u30ec\u30a4\u30d7: ${scrapingTaskCount}\u4ef6 / \u7ffb\u8a33: ${uiState.translationProgress.first}/${uiState.translationProgress.second}\u4ef6"
                    scrapingTaskCount > 0 ->
                        "\u5b9f\u884c\u4e2d: ${scrapingTaskCount} \u4ef6"
                    else ->
                        "\u7ffb\u8a33\u4e2d: ${uiState.translationProgress.first}/${uiState.translationProgress.second}\u4ef6 (${uiState.translationStatusText})"
                }
                intent.putExtra(ScraperService.EXTRA_MSG, msg)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                stopService(intent)
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