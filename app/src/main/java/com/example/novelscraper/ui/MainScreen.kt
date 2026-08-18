package com.example.novelscraper.ui

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.*
import com.example.novelscraper.ui.components.*
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun MainScreen(
    viewModel: ScrapingViewModel,
    onStartScraping: (String) -> Unit,
    onResumeScraping: (String, String) -> Unit,
    onInspectResult: (String, String) -> Unit,
    onLaunchAnalysisTool: (WebView) -> Unit,
    onInjectInspector: (WebView) -> Unit,
    onNavigate: (String, WebView) -> Unit,
    onShowAddFavorite: (String, String) -> Unit,
    onShowSavePreset: () -> Unit,
    onTestRun: (WebView) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val currentStatusText by viewModel.currentStatusText.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

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
            viewModel.setTranslationFolder(treeUri, folderName)
        }
    }

    BackHandler(enabled = uiState.openedPanel != PanelType.NONE || (webViewRef?.canGoBack() == true)) {
        if (uiState.openedPanel != PanelType.NONE) viewModel.closePanels()
        else if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
    }

    // URL変更時のナビゲーション（正規化比較により同一URLの不要な二重リロードを完全防止）
    LaunchedEffect(uiState.currentUrl) {
        val view = webViewRef
        if (view != null && uiState.currentUrl.isNotEmpty()) {
            val currentNormalized = view.url?.trimEnd('/') ?: ""
            val targetNormalized = uiState.currentUrl.trimEnd('/')
            if (currentNormalized != targetNormalized) {
                onNavigate(uiState.currentUrl, view)
            }
        }
    }

    // ダークモード状態変更時のみ的確に1度だけWebViewへ適用（重複実行を完全排除）
    LaunchedEffect(uiState.isDarkMode) {
        webViewRef?.let { view ->
            WebViewHelper.applyDarkMode(view, uiState.isDarkMode)
        }
    }

    Scaffold(
        topBar = {
            HeaderToolbar(
                uiState = uiState,
                onBackClick = { webViewRef?.goBack() },
                onForwardClick = { webViewRef?.goForward() },
                onStarClick = { webViewRef?.let { onShowAddFavorite(it.title ?: "", it.url ?: "") } },
                onUrlSubmit = { url -> 
                    webViewRef?.let { onNavigate(url, it) }
                    viewModel.setInputUrl(url)
                    viewModel.closePanels()
                },
                onUrlChange = { viewModel.setInputUrl(it) },
                onPanelToggle = { viewModel.setOpenedPanel(it) },
                onInspectModeToggle = {
                    viewModel.toggleInspectMode()
                    if (!uiState.isInspectMode) webViewRef?.let { onInjectInspector(it) } else webViewRef?.reload()
                },
                onInspectToolClick = { webViewRef?.let { onLaunchAnalysisTool(it) } },
                onToggleDesktopModeClick = { viewModel.toggleDesktopMode() },
                onToggleDarkModeClick = { viewModel.toggleDarkMode() },
                onStartScrapingClick = { onStartScraping(webViewRef?.url ?: uiState.inputUrl) },
                onTestRunClick = { webViewRef?.let { onTestRun(it) } },
                onToggleImagesClick = { viewModel.toggleBlockImages() },
                isDesktopMode = uiState.isDesktopMode,
                isDarkMode = uiState.isDarkMode
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.let { view ->
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.webViewClient = android.webkit.WebViewClient()
                    view.webChromeClient = null
                    view.destroy()
                }
                webViewRef = null
            }
        }

        // ルートの imePadding() を排除（WebViewのリフロー防止。必要なパネル内部にのみ局所適用）
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // 背景レイヤー: WebView & ステータスバー
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx.applicationContext).apply {
                            WebViewHelper.applyStandardSettings(this, !uiState.blockImages, uiState.isDesktopMode)
                            WebViewHelper.applyDarkMode(this, uiState.isDarkMode)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    // ページ読み込み開始直後の0msで先行注入（白チラつき完全根絶）
                                    if (uiState.isDarkMode) {
                                        view?.evaluateJavascript(WebViewHelper.buildDarkModeJs(true), null)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url != null && !url.startsWith("javascript:") && !url.startsWith("data:")) {
                                        viewModel.setCurrentUrl(url)
                                        viewModel.setInputUrl(url)
                                    }
                                    if (uiState.isDarkMode) {
                                        view?.evaluateJavascript(WebViewHelper.buildDarkModeJs(true), null)
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
                        val targetUA = if (uiState.isDesktopMode) WebViewHelper.DESKTOP_UA else WebViewHelper.MOBILE_UA
                        if (view.settings.userAgentString != targetUA) {
                            view.settings.userAgentString = targetUA
                            view.reload()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            clip = true
                            // パネル表示中はGPU描画コマンドの発行を完全スキップ（オクルージョン・カリング）
                            alpha = if (uiState.openedPanel == PanelType.NONE) 1f else 0f
                        }
                )

                Text(
                    text = if (uiState.isTranslating) "翻訳: ${uiState.translationStatusText}" else currentStatusText,
                    modifier = Modifier.fillMaxWidth().background(AppColors.backgroundMedium).padding(4.dp),
                    color = if (uiState.isTranslating) AppColors.accentTealLight else AppColors.textMuted,
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
                        onSavePresetClick = { onShowSavePreset() },
                        onDeletePresetClick = { viewModel.deletePreset(uiState.currentPresetName) },
                        onConfigChange = { newConfig -> viewModel.updateCurrentConfig { newConfig } },
                        onImportPresetsClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        onExportPresetsClick = { exportLauncher.launch("novel_scraper_presets.json") }
                    )
                }
                PanelType.HISTORY -> {
                    HistoryPanel(
                        activeTab = uiState.activeHistoryTab,
                        onTabSelected = { viewModel.setActiveHistoryTab(it) },
                        activeTasks = activeTasks,
                        history = history,
                        onStopTaskClick = { task -> task.stop(); viewModel.removeTask(task) },
                        onHistoryItemClick = { url -> 
                            webViewRef?.let { onNavigate(url, it) }
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
                                onResumeScraping(item.nextUrl, folder)
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
                            webViewRef?.let { onNavigate(url, it) }
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
                        onSelectFolderClick = { folderLauncher.launch(null) },
                        onStartTranslationClick = { viewModel.startTranslation() },
                        onStopTranslationClick = { viewModel.stopTranslation() },
                        onOpenGoogleTranslateClick = {
                            val gUrl = "https://translate.google.com/?sl=${uiState.translationSourceLang}&tl=${uiState.translationTargetLang}&op=translate"
                            webViewRef?.let { onNavigate(gUrl, it) }
                            viewModel.setCurrentUrl(gUrl)
                            viewModel.setInputUrl(gUrl)
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
    }
}
