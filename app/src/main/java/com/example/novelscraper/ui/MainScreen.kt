package com.example.novelscraper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.ActiveDialog
import com.example.novelscraper.EngineTranslationState
import com.example.novelscraper.MainUiState
import com.example.novelscraper.Overlay
import com.example.novelscraper.PanelType
import com.example.novelscraper.ScrapingViewModel
import com.example.novelscraper.TranslationEngine
import com.example.novelscraper.WebViewHelper
import com.example.novelscraper.NativeWebTranslator
import com.example.novelscraper.ui.components.*
import com.example.novelscraper.ui.theme.AppColors

/**
 * 原文復帰リロード後に自動実行する保留アクション。
 */
private enum class PendingPostReloadAction {
    NONE,
    TEST_RUN,
    INSPECT_MODE
}

/**
 * MainScreen と Activity の境界コールバック集約。
 */
data class MainScreenCallbacks(
    val onStartScraping: (String) -> Unit,
    val onResumeScraping: (String, String) -> Unit,
    val onLaunchAnalysisTool: (WebView) -> Unit,
    val onSetupWebView: (WebView) -> Unit,
    val onInjectInspector: (WebView?) -> Unit,
    val onRemoveInspector: (WebView?) -> Unit,
    val onNavigate: (String, WebView) -> Unit,
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
    var pendingPostReloadAction by remember { mutableStateOf(PendingPostReloadAction.NONE) }

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
            } catch (_: Exception) {
                // セキュリティ例外等のハンドリング
            }
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            val folderName = doc?.name ?: treeUri.lastPathSegment ?: "選択フォルダ"
            viewModel.addTranslationFolder(uiState.activeTranslationEngine, treeUri, folderName)
        }
    }

    // インスペクター注入/破棄の単一管理（状態変化のみをトリガーにする）
    val inspectActive = uiState.overlay is Overlay.InspectMode
    LaunchedEffect(inspectActive) {
        if (inspectActive) {
            callbacks.onInjectInspector(webViewRef)
        } else {
            callbacks.onRemoveInspector(webViewRef)
        }
    }

    BackHandler(enabled = uiState.activeDialog !is ActiveDialog.None || uiState.overlay != Overlay.None || (webViewRef?.canGoBack() == true)) {
        if (uiState.activeDialog !is ActiveDialog.None) {
            viewModel.dismissDialog()
            return@BackHandler
        }
        val ov = uiState.overlay
        when {
            ov is Overlay.TestResult && ov.excludeCandidates != null -> viewModel.setExcludeCandidates(null)
            ov is Overlay.TestResult -> viewModel.setTestResult(null)
            ov is Overlay.InspectMode -> viewModel.setInspectMode(false)
            ov is Overlay.Panel -> viewModel.closePanels()
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
                        viewModel.showAddFavoriteDialog(title, url)
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
                    // 安全ガード: 翻訳ONの時は即座に原文復帰（リロードなし）してからインスペクター起動
                    if (uiState.isWebPageTranslated) {
                        viewModel.setWebPageTranslated(false)
                        webViewRef?.evaluateJavascript(NativeWebTranslator.buildRestoreScript(), null)
                    }
                    viewModel.setInspectMode(!uiState.isInspectMode)
                },
                onInspectToolClick = {
                    webViewRef?.let { callbacks.onLaunchAnalysisTool(it) }
                },
                onTestRunClick = {
                    if (uiState.overlay is Overlay.TestResult) {
                        viewModel.setTestResult(null)
                    } else {
                        // 安全ガード: 翻訳ONの時は即座に原文復帰（リロードなし）してからテスト解析を実行
                        if (uiState.isWebPageTranslated) {
                            viewModel.setWebPageTranslated(false)
                            webViewRef?.evaluateJavascript(NativeWebTranslator.buildRestoreScript(), null)
                        }
                        webViewRef?.let { callbacks.onTestRun(it) }
                    }
                },
                onToggleDesktopModeClick = {
                    viewModel.setDesktopMode(!uiState.isDesktopMode)
                },
                onToggleDarkModeClick = {
                    viewModel.toggleWebViewDarkMode()
                },
                onToggleWebTranslateClick = {
                    val isCurrentlyTranslated = uiState.isWebPageTranslated
                    val nextState = !isCurrentlyTranslated
                    viewModel.setWebPageTranslated(nextState)
                    webViewRef?.let { view ->
                        val jsCode = if (nextState) {
                            NativeWebTranslator.buildExtractScript()
                        } else {
                            NativeWebTranslator.buildRestoreScript()
                        }
                        view.evaluateJavascript(jsCode, null)
                    }
                },
                onStartScrapingClick = {
                    val url = uiState.currentUrl
                    if (url.isNotEmpty()) {
                        // 安全ガード: 翻訳状態をOFFにし、原文テキストに復帰（リロードなし）
                        if (uiState.isWebPageTranslated) {
                            viewModel.setWebPageTranslated(false)
                            webViewRef?.evaluateJavascript(NativeWebTranslator.buildRestoreScript(), null)
                        }
                        val currentTask = activeTasks.firstOrNull { it.currentUrl == url }
                        if (currentTask != null) {
                            currentTask.stop()
                        } else {
                            callbacks.onStartScraping(url)
                        }
                    }
                }
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

                                    // 保留アクション（テスト実行 / インスペクター）の自動実行（二度手間解消）
                                    when (pendingPostReloadAction) {
                                        PendingPostReloadAction.TEST_RUN -> {
                                            pendingPostReloadAction = PendingPostReloadAction.NONE
                                            view?.let { callbacks.onTestRun(it) }
                                        }
                                        PendingPostReloadAction.INSPECT_MODE -> {
                                            pendingPostReloadAction = PendingPostReloadAction.NONE
                                            viewModel.setInspectMode(true)
                                        }
                                        PendingPostReloadAction.NONE -> {
                                            if (uiState.isInspectMode) {
                                                callbacks.onInjectInspector(view)
                                            }
                                        }
                                    }
                                }
                            }
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        // UIスレッドを同期ブロックする view.visibility 切り替えを廃止し、
                        // タッチ入力と graphicsLayer アルファのみで安全・瞬時に制御
                        view.isEnabled = uiState.overlay == Overlay.None || uiState.overlay is Overlay.InspectMode
                        if (view.settings.blockNetworkImage != uiState.blockImages) {
                            view.settings.blockNetworkImage = uiState.blockImages
                        }
                        val targetUA = WebViewHelper.getUserAgent(view.context, isDesktop = uiState.isDesktopMode)
                        if (view.settings.userAgentString != targetUA) {
                            view.settings.userAgentString = targetUA
                            view.reload()
                        }
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
                            alpha = if (uiState.overlay == Overlay.None) 1f else 0f
                        }
                )

                // 独立したステータスバー（翻訳高頻度更新時のリコンポジションを局所化）
                AppStatusBar(
                    isAnyTranslating = uiState.isAnyTranslating,
                    googleState = uiState.googleTranslationState,
                    deeplState = uiState.deeplTranslationState,
                    currentStatusText = currentStatusText
                )
            }

            // 前面レイヤー: パネル群
            when (val ov = uiState.overlay) {
                is Overlay.Panel -> when (ov.type) {
                    PanelType.SETTINGS -> {
                        SettingsPanel(
                            currentConfig = uiState.currentConfig,
                            currentPresetName = uiState.currentPresetName,
                            isWebViewDarkMode = uiState.isWebViewDarkMode,
                            presets = presets,
                            onCloseClick = { viewModel.closePanels() },
                            onPresetSelected = { name, config -> viewModel.applyPresetState(name, config) },
                            onSavePresetClick = { viewModel.showSavePresetDialog(uiState.currentPresetName, uiState.currentUrl) },
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
                                    Toast.makeText(context, "次のページが見つかりません（最新話か、古い履歴です）", Toast.LENGTH_LONG).show()
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
                }
                is Overlay.TestResult -> {
                    TestResultPanel(
                        result = ov.result,
                        onDismiss = { viewModel.setTestResult(null) },
                        onRequestExclude = callbacks.onRequestExclude
                    )
                    ov.excludeCandidates?.let { candidates ->
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
                else -> {}
            }

            // Compose 駆動ダイアログ群
            when (val dialog = uiState.activeDialog) {
                is ActiveDialog.AddFavorite -> {
                    AddFavoriteDialog(
                        initialTitle = dialog.title,
                        onConfirm = { name ->
                            viewModel.saveFavorite(name, dialog.url)
                            viewModel.dismissDialog()
                            Toast.makeText(context, "お気に入りに追加しました", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { viewModel.dismissDialog() }
                    )
                }
                is ActiveDialog.SavePreset -> {
                    SavePresetDialog(
                        defaultPresetName = dialog.defaultName,
                        currentUrl = dialog.currentUrl,
                        existingPresets = presets,
                        onConfirm = { name ->
                            viewModel.savePreset(name, uiState.currentConfig)
                            viewModel.dismissDialog()
                            Toast.makeText(context, "プリセット「$name」を保存しました", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { viewModel.dismissDialog() }
                    )
                }
                is ActiveDialog.InspectElement -> {
                    InspectElementDialog(
                        initialSelector = dialog.selector,
                        onApply = { field, sel ->
                            viewModel.applySelectorToConfig(field, sel)
                            viewModel.dismissDialog()
                            Toast.makeText(context, "${field.displayName} にセレクタを反映しました", Toast.LENGTH_SHORT).show()
                        },
                        onCopy = { sel ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Selector", sel))
                            viewModel.dismissDialog()
                            Toast.makeText(context, "セレクタをコピーしました", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { viewModel.dismissDialog() }
                    )
                }
                ActiveDialog.None -> {}
            }
        }
    }
}

/**
 * 下部ステータスバー（独立コンポーネント化によりリコンポジションを局所化）
 */
@Composable
private fun AppStatusBar(
    isAnyTranslating: Boolean,
    googleState: EngineTranslationState,
    deeplState: EngineTranslationState,
    currentStatusText: String
) {
    val translationStatus = when {
        googleState.isTranslating && deeplState.isTranslating ->
            "Google: ${googleState.progress.first}/${googleState.progress.second}件 | DeepL: ${deeplState.progress.first}/${deeplState.progress.second}件"
        googleState.isTranslating ->
            "Google翻訳: ${googleState.statusText}"
        deeplState.isTranslating ->
            "DeepL翻訳: ${deeplState.statusText}"
        else -> currentStatusText
    }

    Text(
        text = if (isAnyTranslating) translationStatus else currentStatusText,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.backgroundMedium)
            .padding(4.dp),
        color = if (isAnyTranslating) AppColors.accentTealLight else AppColors.textMuted,
        fontSize = 11.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}