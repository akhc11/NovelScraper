package com.example.novelscraper.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.MainUiState
import com.example.novelscraper.PanelType
import com.example.novelscraper.ScraperConfig
import com.example.novelscraper.ScrapingViewModel
import com.example.novelscraper.TranslationEngine
import com.example.novelscraper.WebViewHelper
import com.example.novelscraper.ui.components.*
import com.example.novelscraper.ui.theme.AppColors

/**
 * MainScreen と Activity の境界コールバック集約（引数爆発の構造的解消）。
 */
data class MainScreenCallbacks(
    val onStartScraping: (String) -> Unit,
    val onResumeScraping: (String, String) -> Unit,
    val onLaunchAnalysisTool: (WebView) -> Unit,
    val onSetupWebView: (WebView) -> Unit,
    val onInjectInspector: (WebView?) -> Unit,
    val onRemoveInspector: (WebView?) -> Unit,
    val onNavigate: (String, WebView) -> Unit,
    val onShowAddFavorite: (String, String) -> Unit,
    val onShowSavePreset: () -> Unit,
    val onTestRun: (WebView) -> Unit,
    val onRequestExclude: (String) -> Unit
)

@Composable
fun MainScreen(
    viewModel: ScrapingViewModel,
    callbacks: MainScreenCallbacks
) {
    val uiState by viewModel.uiState.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val currentStatusText by viewModel.currentStatusText.collectAsState()
    val context = LocalContext.current

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportPresets(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importPresets(it) }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // セキュリティ例外等のハンドリング
            }
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            val folderName = doc?.name ?: treeUri.lastPathSegment ?: "選択フォルダ"
            viewModel.addTranslationFolder(uiState.activeTranslationEngine, treeUri, folderName)
        }
    }

    // インスペクター注入/破棄の単一管理（状態変化のみをトリガーにする）
    LaunchedEffect(uiState.isInspectMode) {
        if (uiState.isInspectMode) {
            callbacks.onInjectInspector(webViewRef)
        } else {
            callbacks.onRemoveInspector(webViewRef)
        }
    }

    BackHandler(enabled = uiState.excludeCandidates != null || uiState.testResult != null || uiState.isInspectMode || uiState.openedPanel != PanelType.NONE || (webViewRef?.canGoBack() == true)) {
        when {
            uiState.excludeCandidates != null -> viewModel.setExcludeCandidates(null)
            uiState.testResult != null -> viewModel.setTestResult(null)
            uiState.isInspectMode -> viewModel.setInspectMode(false)
            uiState.openedPanel != PanelType.NONE -> viewModel.closePanels()
            else -> webViewRef?.let { view ->
                if (view.canGoBack()) view.goBack()
            }
        }
    }

    // URL変更時のナビゲーション（正規化比較により同一URLの不要な二重リロードを完全防止）
    LaunchedEffect(uiState.currentUrl) {
        val view = webViewRef
        if (view != null && uiState.currentUrl.isNotEmpty()) {
            val currentNormalized = view.url?.trimEnd('/') ?: ""
            val targetNormalized = uiState.currentUrl.trimEnd('/')
            if (currentNormalized != targetNormalized) {
                callbacks.onNavigate(uiState.currentUrl, view)
            }
        }
    }

    Scaffold(
        topBar = {
            HeaderToolbar(
                uiState = uiState,
                onBackClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                onForwardClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                onStarClick = {
                    val url = webViewRef?.url ?: uiState.currentUrl
                    val title = webViewRef?.title ?: "No Title"
                    if (url.isNotEmpty()) {
                        callbacks.onShowAddFavorite(title, url)
                    }
                },
                onStarLongClick = {
                    viewModel.togglePanel(PanelType.FAVORITES)
                },
                onUrlSubmit = { url ->
                    viewModel.setInputUrl(url)
                    webViewRef?.let { view -> callbacks.onNavigate(url, view) }
                },
                onUrlChange = { viewModel.setInputUrl(it) },
                onPanelToggle = { panel -> viewModel.togglePanel(panel) },
                onInspectModeToggle = {
                    // 注入/破棄は LaunchedEffect(isInspectMode) が単一管理
                    viewModel.setInspectMode(!uiState.isInspectMode)
                },
                onInspectToolClick = {
                    webViewRef?.let { callbacks.onLaunchAnalysisTool(it) }
                },
                onTestRunClick = {
                    // トグル動作: 結果パネル表示中は閉じる、非表示ならテスト実行して開く
                    if (uiState.testResult != null) {
                        viewModel.setTestResult(null)
                    } else {
                        webViewRef?.let { callbacks.onTestRun(it) }
                    }
                },
                onToggleDesktopModeClick = {
                    val nextDesktop = !uiState.isDesktopMode
                    viewModel.setDesktopMode(nextDesktop)
                },
                onToggleDarkModeClick = {
                    viewModel.toggleWebViewDarkMode()
                },
                onStartScrapingClick = {
                    val url = uiState.currentUrl
                    if (url.isNotEmpty()) {
                        val currentTask = activeTasks.firstOrNull { it.currentUrl == url }
                        if (currentTask != null) {
                            currentTask.stop()
                        } else {
                            callbacks.onStartScraping(url)
                        }
                    }
                },
                onToggleImagesClick = {
                    val nextBlock = !uiState.blockImages
                    viewModel.setBlockImages(nextBlock)
                },
                isDesktopMode = uiState.isDesktopMode
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 背景レイヤー: WebView（画面遷移を行わず常駐）
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webChromeClient = android.webkit.WebChromeClient()
                            WebViewHelper.applyStandardSettings(this, isDesktop = uiState.isDesktopMode)
                            WebViewHelper.applyDarkMode(this, uiState.isWebViewDarkMode)
                            tag = uiState.isWebViewDarkMode
                            callbacks.onSetupWebView(this)
                            
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    url?.let {
                                        if (it.isNotEmpty() && !it.startsWith("javascript:")) {
                                            viewModel.setCurrentUrl(it)
                                        }
                                    }
                                    if (uiState.isInspectMode) {
                                        callbacks.onInjectInspector(view)
                                    }
                                }
                            }
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        // 画像ブロックの変更時のみ設定を更新
                        if (view.settings.blockNetworkImage != uiState.blockImages) {
                            view.settings.blockNetworkImage = uiState.blockImages
                        }
                        // デスクトップモードの変更時のみUA更新＆リロード
                        val targetUA = WebViewHelper.getUserAgent(view.context, isDesktop = uiState.isDesktopMode)
                        if (view.settings.userAgentString != targetUA) {
                            view.settings.userAgentString = targetUA
                            view.reload()
                        }
                        // ダークモード状態の更新
                        val lastDarkMode = view.tag as? Boolean
                        if (lastDarkMode != uiState.isWebViewDarkMode) {
                            view.tag = uiState.isWebViewDarkMode
                            WebViewHelper.applyDarkMode(view, uiState.isWebViewDarkMode)
                            view.reload()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                    .graphicsLayer {
                        clip = true
                        // パネル・テスト結果表示中はGPU描画コマンドの発行を完全スキップ（オクルージョン・カリング）
                        alpha = if (uiState.openedPanel == PanelType.NONE && uiState.testResult == null) 1f else 0f
                    }
                )

                val isAnyTranslating = uiState.isAnyTranslating
                val translationStatus = when {
                    uiState.googleTranslationState.isTranslating && uiState.deeplTranslationState.isTranslating ->
                        "Google: ${uiState.googleTranslationState.progress.first}/${uiState.googleTranslationState.progress.second}件 | DeepL: ${uiState.deeplTranslationState.progress.first}/${uiState.deeplTranslationState.progress.second}件"
                    uiState.googleTranslationState.isTranslating ->
                        "Google翻訳: ${uiState.googleTranslationState.statusText}"
                    uiState.deeplTranslationState.isTranslating ->
                        "DeepL翻訳: ${uiState.deeplTranslationState.statusText}"
                    else -> currentStatusText
                }

                Text(
                    text = if (isAnyTranslating) translationStatus else currentStatusText,
                    modifier = Modifier.fillMaxWidth().background(AppColors.backgroundMedium).padding(4.dp),
                    color = if (isAnyTranslating) AppColors.accentTealLight else AppColors.textMuted,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            // 前面レイヤー: パネル群（ゼロ遅延・GPUアルファ合成なしの完全不透明スタック描画）
            when (uiState.openedPanel) {
                PanelType.SETTINGS -> {
                    SettingsPanel(
                        uiState = uiState,
                        presets = presets,
                        onCloseClick = { viewModel.closePanels() },
                        onPresetSelected = { name, config -> viewModel.applyPresetState(name, config) },
                        onSavePresetClick = { callbacks.onShowSavePreset() },
                        onDeletePresetClick = { viewModel.deletePreset(uiState.currentPresetName) },
                        onConfigChange = { newConfig -> viewModel.updateCurrentConfig { newConfig } },
                        onImportPresetsClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        onExportPresetsClick = { exportLauncher.launch("novel_scraper_presets.json") },
                        onToggleWebViewDarkModeClick = { viewModel.toggleWebViewDarkMode() }
                    )
                }
                PanelType.HISTORY -> {
                    HistoryPanel(
                        activeTab = uiState.activeHistoryTab,
                        onTabSelected = { viewModel.setActiveHistoryTab(it) },
                        activeTasks = activeTasks,
                        history = history,
                        onCloseClick = { viewModel.closePanels() },
                        onStopTaskClick = { task -> task.stop(); viewModel.removeTask(task) },
                        onHistoryItemClick = { url -> 
                            webViewRef?.let { callbacks.onNavigate(url, it) }
                            viewModel.setCurrentUrl(url)
                            viewModel.setInputUrl(url)
                            viewModel.closePanels()
                        },
                        onHistoryResumeClick = { folder ->
                            val item = history[folder] ?: return@HistoryPanel
                            if (item.nextUrl.isNotEmpty()) {
                                val lastNum = item.chapter.filter { it.isDigit() }.toIntOrNull() ?: 0
                                val nextConfig = item.config.copy(chapter = "@${lastNum + 1}")
                                val presetName = if (item.presetName.isNotEmpty()) item.presetName else "(履歴から再開)"
                                viewModel.applyPresetState(presetName, nextConfig)
                                callbacks.onResumeScraping(item.nextUrl, folder)
                                viewModel.closePanels()
                            } else {
                                android.widget.Toast.makeText(context, "次のページが見つかりません（最新話か、古い履歴です）", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onDeleteHistoryClick = { folder -> viewModel.deleteHistory(folder) }
                    )
                }
                PanelType.FAVORITES -> {
                    FavoritesPanel(
                        favorites = favorites,
                        onFavoriteClick = { url -> 
                            webViewRef?.let { callbacks.onNavigate(url, it) }
                            viewModel.setCurrentUrl(url)
                            viewModel.setInputUrl(url)
                            viewModel.closePanels() 
                        },
                        onDeleteClick = { name -> viewModel.deleteFavorite(name) },
                        onCloseClick = { viewModel.closePanels() }
                    )
                }
                PanelType.TRANSLATION -> {
                    TranslationPanel(
                        uiState = uiState,
                        onSelectEngineTab = { engine -> viewModel.setActiveTranslationEngine(engine) },
                        onSelectFolderClick = { folderLauncher.launch(null) },
                        onRemoveFolderClick = { engine, index -> viewModel.removeTranslationFolder(engine, index) },
                        onClearFoldersClick = { engine -> viewModel.clearTranslationFolders(engine) },
                        onUpdateDelays = { engine, chunkDelay, fileDelay -> viewModel.updateTranslationDelays(engine, chunkDelay, fileDelay) },
                        onStartTranslationClick = { engine -> viewModel.startTranslation(engine) },
                        onStopTranslationClick = { engine -> viewModel.stopTranslation(engine) },
                        onOpenWebTranslateClick = { engine ->
                            val targetUrl = when (engine) {
                                TranslationEngine.GOOGLE -> "https://translate.google.com/?sl=auto&tl=ja&op=translate"
                                TranslationEngine.DEEPL -> "https://www.deepl.com/ja/translator#auto/ja/"
                            }
                            webViewRef?.let { callbacks.onNavigate(targetUrl, it) }
                            viewModel.setCurrentUrl(targetUrl)
                            viewModel.setInputUrl(targetUrl)
                            viewModel.closePanels()
                        },
                        onCloseClick = { viewModel.closePanels() }
                    )
                }
                PanelType.NONE -> {
                    // パネル非表示
                }
            }
        }

        // 前面レイヤー: テスト結果パネル（非モーダルoverlay・オーバーレイ単一化は ViewModel 担当）
        uiState.testResult?.let { result ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                TestResultPanel(
                    result = result,
                    onDismiss = { viewModel.setTestResult(null) },
                    onRequestExclude = callbacks.onRequestExclude
                )
                uiState.excludeCandidates?.let { candidates ->
                    ExcludeCandidatesCard(
                        state = candidates,
                        onApply = { selector ->
                            viewModel.addExcludeSelector(selector)
                            viewModel.setExcludeCandidates(null)
                            webViewRef?.let { callbacks.onTestRun(it) }
                        },
                        onDismiss = { viewModel.setExcludeCandidates(null) }
                    )
                }
            }
        }
    }
}
