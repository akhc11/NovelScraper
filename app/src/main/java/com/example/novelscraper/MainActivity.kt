package com.example.novelscraper

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
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
    private lateinit var fileRepository: FileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ScrapingViewModel::class.java]
        fileRepository = FileRepository(this)
        setupSystemUI()
        checkNotificationPermission()

        setContent {
            NovelScraperTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStartScraping = { url -> startScrapingTask(url) },
                    onResumeScraping = { url, folder -> startScrapingTask(url, folder) },
                    onInspectResult = { sel, text -> showInspectResultDialog(sel, text) },
                    onLaunchAnalysisTool = { view -> launchAnalysisTool(view) },
                    onInjectInspector = { view -> injectInspector(view) },
                    onNavigate = { url, view -> performNavigation(url, view) },
                    onShowAddFavorite = { title, url -> showAddFavoriteDialog(title, url) },
                    onShowSavePreset = { showSavePresetDialog() },
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
        val target = if (android.util.Patterns.WEB_URL.matcher(input).matches() || android.webkit.URLUtil.isValidUrl(input)) {
            if (!input.startsWith("http")) "https://$input" else input
        } else {
            "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
        }
        view.loadUrl(target)
    }

    private fun showAddFavoriteDialog(title: String, url: String) {
        val input = EditText(this)
        input.setText(title)
        AlertDialog.Builder(this)
            .setTitle("お気に入り")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.saveFavorite(name, url)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this)
        input.hint = "プリセット名"
        AlertDialog.Builder(this)
            .setTitle("保存")
            .setPositiveButton("保存") { _, _ ->
                var name = input.text.toString().trim()
                val currentPreset = viewModel.uiState.value.currentPresetName
                if (name.isEmpty()) {
                    name = if (currentPreset.isNotEmpty()) {
                        val match = Regex("""(.+) \((\d+)\)$""").find(currentPreset)
                        if (match != null) {
                            "${match.groupValues[1]} (${match.groupValues[2].toInt() + 1})"
                        } else {
                            "$currentPreset (1)"
                        }
                    } else {
                        try {
                            Uri.parse(viewModel.uiState.value.currentUrl).host ?: "Preset"
                        } catch (e: Exception) {
                            "Preset"
                        }
                    }
                }
                while (viewModel.presets.value.containsKey(name)) {
                    val match = Regex("""(.+) \((\d+)\)$""").find(name)
                    name = if (match != null) {
                        "${match.groupValues[1]} (${match.groupValues[2].toInt() + 1})"
                    } else {
                        "$name (1)"
                    }
                }
                viewModel.savePreset(name, viewModel.uiState.value.currentConfig)
            }
            .setNegativeButton("キャンセル", null)
            .setView(input)
            .show()
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

                    val msg = "作品: ${data.folderName}\n話: $chap\nタイトル: ${data.title}\n次: ${data.nextUrl}\n\n本文:\n${data.content.take(300)}..."
                    AlertDialog.Builder(this)
                        .setTitle("テスト結果")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
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
    }

    private fun launchAnalysisTool(view: WebView) {
        view.evaluateJavascript(ScrapingScriptBuilder.buildErudaScript(), null)
    }

    private fun startScrapingTask(targetUrl: String, initialFolderName: String = "(取得中...)") {
        val config = viewModel.uiState.value.currentConfig
        val newTask = ScrapingTask(
            this, targetUrl, config,
            !viewModel.uiState.value.blockImages,
            viewModel.uiState.value.isDesktopMode,
            initialFolderName,
            object : ScrapingTask.TaskListener {
                override fun onStatusUpdate(task: ScrapingTask, status: String) {
                    viewModel.updateStatus()
                    updateServiceStatus()
                }
                override fun onTaskFinished(task: ScrapingTask) {
                    viewModel.removeTask(task)
                    updateServiceStatus()
                }
                override fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String) {
                    fileRepository.saveChapter(folderName, title, content, chapterNum)
                }
                override fun onUpdateHistory(folderName: String, title: String, chapter: String, url: String, config: ScraperConfig) {
                    viewModel.updateHistory(folderName, title, chapter, url, config)
                }
            }
        )
        viewModel.addTask(newTask)
        newTask.start()
        updateServiceStatus()
        viewModel.closePanels()
    }

    private fun injectInspector(view: WebView?) {
        view?.let { v ->
            v.evaluateJavascript(ScrapingScriptBuilder.buildInspectorScript(), null)
            v.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, msg: String?, res: JsResult?): Boolean {
                    if (msg?.startsWith("INSPECT:") == true) {
                        try {
                            val info = JSONObject(msg.substring(8))
                            showInspectResultDialog(info.getString("selector"), info.getString("text"))
                        } catch (e: Exception) { /* インスペクタ結果のパース失敗は無視 */ }
                        res?.confirm()
                        return true
                    }
                    return super.onJsAlert(view, url, msg, res)
                }
            }
        }
    }

    private fun showInspectResultDialog(selector: String, textPreview: String) {
        val input = EditText(this)
        input.setText(selector)
        AlertDialog.Builder(this)
            .setTitle("セレクタ取得")
            .setMessage("コピーしますか？")
            .setView(input)
            .setPositiveButton("コピー") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Selector", input.text.toString()))
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun updateServiceStatus() {
        mainHandler.post {
            val count = viewModel.activeTasks.value.size
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