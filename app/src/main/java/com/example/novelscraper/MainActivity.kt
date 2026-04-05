package com.example.novelscraper

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.novelscraper.ui.MainScreen
import com.example.novelscraper.ui.theme.NovelScraperTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONObject

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

    private fun performNavigation(input: String, view: WebView) {
        if (input.isEmpty()) return

        // 異常に長い入力（過去のバグによるスクリプトデータの混入など）に対する保護
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
            viewModel.presets.collect { presets ->
                if (viewModel.uiState.value.currentPresetName.isEmpty() && presets.containsKey("初期設定(小説家になろう)")) {
                    presets["初期設定(小説家になろう)"]?.let {
                        viewModel.applyPresetState("初期設定(小説家になろう)", it)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.activeTasks.collect { tasks ->
                updateServiceStatus(tasks.size)
            }
        }
    }

    private fun launchAnalysisTool(view: WebView) {
        view.evaluateJavascript(ScrapingScriptBuilder.buildErudaScript(), null)
    }



    private fun injectInspector(view: WebView?) {
        view?.let { v ->
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorScript(), null)
            v.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, msg: String?, res: JsResult?): Boolean {
                    if (msg?.startsWith("INSPECT:") == true) {
                        try {
                            val info = JSONObject(msg.substring(8))
                            DialogHelper.showInspectResultDialog(this@MainActivity, info.getString("selector"))
                        } catch (e: Exception) { /* インスペクタ結果のパース失敗は無視 */ }
                        res?.confirm()
                        return true
                    }
                    return super.onJsAlert(view, url, msg, res)
                }
            }
        }
    }

    private fun updateServiceStatus(count: Int) {
        mainHandler.post {
            val intent = Intent(this, ScraperService::class.java)
            if (count > 0) {
                intent.action = ScraperService.ACTION_UPDATE_STATUS
                intent.putExtra(ScraperService.EXTRA_MSG, "実行中: $count 件")
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