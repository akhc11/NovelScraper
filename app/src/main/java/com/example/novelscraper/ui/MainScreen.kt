package com.example.novelscraper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.novelscraper.*
import com.example.novelscraper.scraper.*
import com.example.novelscraper.translation.picker.PickerRootMenuDialog
import com.example.novelscraper.translation.picker.PickerTargetAdapter
import com.example.novelscraper.translation.picker.RootHealth
import com.example.novelscraper.translation.picker.SafBrowserDialog
import com.example.novelscraper.translation.picker.SafBrowserState
import com.example.novelscraper.translation.picker.SafBrowserViewModel
import com.example.novelscraper.translation.picker.treeDisplayName
import com.example.novelscraper.translation.v2.ui.V2TranslationPanel
import com.example.novelscraper.translation.v2.ui.V2TranslationViewModel
import com.example.novelscraper.ui.components.*
import com.example.novelscraper.ui.theme.AppColors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.json.Json

/**
 * MainScreen から Activity への通知用コールバック群。
 */
data class MainScreenCallbacks(
    val onLaunchAnalysisTool: (WebView) -> Unit,
    val onSetupWebView: (WebView) -> Unit,
    val onInjectInspector: (WebView?) -> Unit,
    val onRemoveInspector: (WebView?) -> Unit,
    val onRequestExclude: (String) -> Unit,
    val onTestRun: (WebView) -> Unit,
    val onToggleLiveTranslate: (WebView?) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
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

    val v2ViewModel: V2TranslationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val v2EngineState by v2ViewModel.engineState.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // レンダラープロセス死亡時の再生成キー（破棄済みWebViewは再利用禁止が公式要件）。
    var webViewKey by remember { mutableIntStateOf(0) }
    // パネル系表示中は背後のWebViewへ一切触らせない（表示層と相互運用層の両方で使う単一判定）。
    val blocksTouchBehindPanel = uiState.overlay is Overlay.Panel || uiState.overlay is Overlay.TestResult
    // 回転・プロセス死からの復帰用にWebViewの履歴・スクロールを退避する（公式PersistentWebView方式）。
    val webViewStateBundle = rememberSaveable { android.os.Bundle() }

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

    var backupIncludeKeys by remember { mutableStateOf(false) }
    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportBackup(it, backupIncludeKeys) }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importBackup(it) }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // セキュリティ例外等のハンドリング
            }
            val folderName = treeDisplayName(context, treeUri)
            viewModel.addTranslationFolder(uiState.activeTranslationEngine, treeUri, folderName)
        }
    }

    // 自前ブラウザ接続(加算のみ。既存ランチャーは不変)。
    val pickerVM: SafBrowserViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val grantedRoots by pickerVM.grantedRoots.collectAsState()
    val rootHealth by pickerVM.rootHealth.collectAsState()
    var showRootMenu by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf<SafBrowserState?>(null) }
    LaunchedEffect(showRootMenu) {
        if (showRootMenu) pickerVM.refreshHealth()
    }

    val grantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val folderName = treeDisplayName(context, treeUri)
            pickerVM.persistRootPermission(treeUri, folderName) { saved ->
                browser = pickerVM.openRoot(saved, folderName)
                showRootMenu = false
            }
        }
    }

    // インスペクター注入/破棄の単一管理（起動中の設定変更はupdateConfigで差分同期・再注入しない）
    val inspectActive = uiState.overlay is Overlay.InspectMode
    LaunchedEffect(inspectActive, webViewRef) {
        if (inspectActive) {
            callbacks.onInjectInspector(webViewRef)
        } else {
            callbacks.onRemoveInspector(webViewRef)
        }
    }
    // 起動中の設定変更（テキスト検索反映・設定手編集）をデバウンスして差分同期。
    // キー入力毎の evaluateJavascript 連打によるページちらつきを防止する。
    LaunchedEffect(inspectActive, webViewRef) {
        if (!inspectActive) return@LaunchedEffect
        snapshotFlow { uiState.currentConfig }
            .drop(1) // 初回分は上記注入済みのため除外
            .debounce(400)
            .collect { callbacks.onInjectInspector(webViewRef) }
    }

    // 戻るボタンのハンドリング
    BackHandler(enabled = true) {
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

    // URL変更時のナビゲーションは行わない（イベント駆動に一本化）。
    // 技術的根拠1行：状態変化の度にloadUrlするとリンクタップ・リダイレクトの現行ロードをcancelし(AOSP)ページ遷移が壊れるため、外部要求はViewModel.navigateTo→Activity収集点で一発loadUrlし、ページ内遷移はWebViewClient報告の表示更新に留める。生成時はfactoryの初期ロードが担う。

    Scaffold(
        topBar = {
            HeaderToolbar(
                uiState = uiState,
                onBackClick = {
                    webViewRef?.let { view ->
                        if (view.canGoBack()) view.goBack()
                    }
                },
                onForwardClick = {
                    webViewRef?.let { view ->
                        if (view.canGoForward()) view.goForward()
                    }
                },
                onStarClick = {
                    val liveUrl = webViewRef?.url?.takeIf { it.isNotBlank() && !it.startsWith("javascript:") && !it.startsWith("data:") }
                    val url = liveUrl ?: uiState.currentUrl
                    if (url.isNotEmpty()) {
                        val title = webViewRef?.title?.ifEmpty { url } ?: url
                        viewModel.showAddFavoriteDialog(title, url)
                    }
                },
                onStarLongClick = {
                    viewModel.togglePanel(PanelType.FAVORITES)
                },
                onUrlSubmit = { url ->
                    // 外部遷移要求は one-shot コマンドで一発配送する（状態駆動loadUrlはしない）。
                    viewModel.navigateTo(url)
                },
                onUrlChange = { viewModel.setInputUrl(it) },
                onPanelToggle = { panel ->
                    viewModel.togglePanel(panel)
                },
                onInspectModeToggle = {
                    viewModel.setInspectMode(!uiState.isInspectMode)
                },
                onInspectToolClick = {
                    webViewRef?.let { callbacks.onLaunchAnalysisTool(it) }
                },
                onToggleDesktopModeClick = {
                    val nextDesktop = !uiState.isDesktopMode
                    viewModel.setDesktopMode(nextDesktop)
                    webViewRef?.let { view ->
                        WebViewHelper.applyStandardSettings(view, isDesktop = nextDesktop)
                        view.evaluateJavascript(WebViewHelper.buildDesktopViewportJs(nextDesktop), null)
                        view.reload()
                    }
                },
                onToggleDarkModeClick = {
                    viewModel.toggleWebViewDarkMode()
                },
                onToggleLiveTranslateClick = {
                    callbacks.onToggleLiveTranslate(webViewRef)
                },
                onStartScrapingClick = {
                    val liveUrl = webViewRef?.url?.takeIf { it.isNotBlank() && !it.startsWith("javascript:") && !it.startsWith("data:") }
                    val url = liveUrl ?: uiState.currentUrl
                    if (url.isNotEmpty()) {
                        if (liveUrl != null && liveUrl != uiState.currentUrl) {
                            viewModel.onWebViewUrlChanged(liveUrl)
                        }
                        val currentTask = activeTasks.firstOrNull { it.currentUrl == url || it.startUrl == url }
                        if (currentTask != null) {
                            // 技術的根拠1行：stopだけでは一覧に死骸が残り以降のタップが永久に空振りするため、停止と同時に除去する。既に死んでいた場合は掃除して新規開始する。
                            val wasRunning = currentTask.isRunning
                            currentTask.stop()
                            viewModel.removeTask(currentTask)
                            if (!wasRunning) {
                                viewModel.startScraping(url)
                            }
                        } else {
                            viewModel.startScraping(url)
                        }
                    }
                },
                onTestRunClick = {
                    // 設定・履歴パネルと同様のトグル動作：表示中なら閉じ、非表示なら実行する。
                    if (uiState.overlay is Overlay.TestResult) {
                        viewModel.setTestResult(null)
                    } else {
                        webViewRef?.let { callbacks.onTestRun(it) }
                    }
                },
                onSearchTextQueryClick = {
                    viewModel.showTextQuerySearchDialog()
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
                // webViewKey変更でfactoryから再生成する（onRenderProcessGone後の復旧用）。
                key(webViewKey) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    consoleMessage?.let {
                                        Log.d("NovelScraperJS", "[${it.messageLevel()}] ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                                    }
                                    return super.onConsoleMessage(consoleMessage)
                                }
                            }
                            WebViewHelper.applyStandardSettings(this, isDesktop = uiState.isDesktopMode)
                            WebViewHelper.applyDarkMode(this, uiState.isWebViewDarkMode)
                            tag = uiState.isWebViewDarkMode
                            callbacks.onSetupWebView(this)
                            // 生成時の初期表示は一度きり。退避済み履歴があれば復元し、なければ共有URL等のcurrentUrlを開く。
                            // 以降の外部遷移はViewModel.navigateTo→Activity収集点の一発loadUrlに任せ、ここでは撃たない。
                            val restored = try {
                                val inner = webViewStateBundle.getBundle("WEBVIEW_STATE")
                                inner != null && restoreState(inner) != null
                            } catch (_: Exception) {
                                false
                            }
                            if (!restored) {
                                val initialUrl = viewModel.uiState.value.currentUrl
                                if (initialUrl.isNotEmpty()) {
                                    loadUrl(initialUrl)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    url?.let {
                                        if (it.isNotEmpty() && !it.startsWith("javascript:") && !it.startsWith("data:")) {
                                            // ページ内遷移の報告は表示更新のみ。loadUrlは発行しない。
                                            viewModel.onWebViewUrlChanged(it)
                                        }
                                    }
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    url?.let {
                                        if (it.isNotEmpty() && !it.startsWith("javascript:") && !it.startsWith("data:")) {
                                            viewModel.onWebViewUrlChanged(it)
                                        }
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    url?.let {
                                        if (it.isNotEmpty() && !it.startsWith("javascript:") && !it.startsWith("data:")) {
                                            viewModel.onWebViewUrlChanged(it)
                                            // LiveTranslateのgoogtransクッキーをここで消さない。
                                            // JSが翻訳トリガに設定した直後のページで消すと競合し、
                                            // 次ページ遷移時の自動継続も阻害するため、明示的な
                                            // startScraping時のみWebViewHelper.clearGoogleTranslateCookiesする。
                                        }
                                    }
                                    view?.evaluateJavascript(WebViewHelper.buildDesktopViewportJs(uiState.isDesktopMode), null)
                                    if (uiState.isInspectMode) {
                                        callbacks.onInjectInspector(view)
                                    }
                                }

                                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                    val didCrash = detail?.didCrash() ?: false
                                    val reason = if (didCrash) "レンダラークラッシュ (C++エラー)" else "メモリ不足によるOS強制終了 (OOM)"
                                    Log.e("MainScreen", "WebView onRenderProcessGone 検知: $reason")
                                    // 公式要件：死亡インスタンスは階層から外してdestroyし、参照を cleared して新規生成する。
                                    try {
                                        view?.destroy()
                                    } catch (_: Exception) {}
                                    webViewRef = null
                                    Toast.makeText(context, "ブラウザ描画プロセスが停止しました ($reason)。再生成します", Toast.LENGTH_LONG).show()
                                    webViewKey++
                                    return true // アプリ本体のクラッシュを100%阻止
                                }
                            }
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        // 技術的根拠1行：子ネイティブViewにはComposeより先にView配送で届き、down/upはCompose消費より先に渡される（公式PointerInteropFilter）。
                        // よってisEnabledやComposeスクリムではタップを止め切れない。重なり表示中はOnTouchListenerで握り潰す（View機構上リスナーが既定動作より先に呼ばれる保証がある）。
                        if (blocksTouchBehindPanel) {
                            view.setOnTouchListener { _, _ -> true }
                        } else {
                            view.setOnTouchListener(null)
                        }
                        if (view.settings.blockNetworkImage != uiState.blockImages) {
                            view.settings.blockNetworkImage = uiState.blockImages
                        }
                        val targetUA = WebViewHelper.getUserAgent(view.context, isDesktop = uiState.isDesktopMode)
                        if (view.settings.userAgentString != targetUA) {
                            view.settings.userAgentString = targetUA
                            view.reload()
                        }
                        if (view.tag != uiState.isWebViewDarkMode) {
                            WebViewHelper.applyDarkMode(view, uiState.isWebViewDarkMode)
                            view.tag = uiState.isWebViewDarkMode
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            clip = true
                            alpha = if (uiState.overlay == Overlay.None || uiState.overlay is Overlay.InspectMode) 1f else 0f
                        }
                        .pointerInput(blocksTouchBehindPanel) {
                            // 技術的根拠1行：相互運用はdown/upをInitialパスでネイティブへ直送するため、Mainパス消費では間に合わない。外側修飾子としてInitialで消費し、内部配送を不発にする。
                            awaitEachGesture {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (blocksTouchBehindPanel) {
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                    onRelease = { view ->
                        // 破棄前に履歴・スクロールを退避し、旧インスタンスは必ず破棄する（回転のたびの蓄積を防ぐ）。
                        // 技術的根拠1行：saveStateは1MB制限超過で例外になり得るため失敗時は退避を諦めてcurrentUrl方式に委ね、destroyは公式要件のため退避成否に関わらず実行する。
                        try {
                            val bundle = android.os.Bundle()
                            if (view.saveState(bundle) != null) {
                                webViewStateBundle.putBundle("WEBVIEW_STATE", bundle)
                            }
                        } catch (_: Exception) {
                        }
                        try {
                            view.destroy()
                        } catch (_: Exception) {
                        }
                        if (webViewRef === view) {
                            webViewRef = null
                        }
                    }
                )
                }

                // 独立したステータスバー（翻訳高頻度更新時のリコンポジションを局所化）
                AppStatusBar(
                    isAnyTranslating = uiState.isAnyTranslating || v2EngineState.isRunning,
                    googleState = uiState.googleTranslationState,
                    deeplState = uiState.deeplTranslationState,
                    isV2Translating = v2EngineState.isRunning,
                    v2StatusText = v2EngineState.statusText,
                    currentStatusText = currentStatusText
                )
            }

            // 虫眼鏡モード中の右下フローティング「文字検索」ボタン
            if (uiState.isInspectMode) {
                FloatingActionButton(
                    onClick = { viewModel.showTextQuerySearchDialog() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 24.dp, end = 16.dp),
                    containerColor = AppColors.accentTeal,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "文字検索", modifier = Modifier.size(18.dp))
                        Text("文字検索", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
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
                            onSavePresetClick = {
                                val domain = uiState.currentUrl.substringAfter("://").substringBefore("/")
                                val defaultName = if (domain.isNotEmpty()) "Preset ($domain)" else "New Preset"
                                viewModel.showSavePresetDialog(defaultName, uiState.currentUrl)
                            },
                            onDeletePresetClick = {
                                if (uiState.currentPresetName.isNotEmpty()) {
                                    viewModel.deletePreset(uiState.currentPresetName)
                                }
                            },
                            onConfigChange = { newConfig -> viewModel.updateCurrentConfig { newConfig } },
                            onImportPresetsClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                            onExportPresetsClick = { exportLauncher.launch("novel_scraper_presets.json") },
                            onExportBackupClick = { includeKeys ->
                                backupIncludeKeys = includeKeys
                                backupExportLauncher.launch("novel_scraper_backup.json")
                            },
                            onImportBackupClick = { backupImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                            onToggleWebViewDarkModeClick = { viewModel.toggleWebViewDarkMode() },
                            currentUrl = uiState.currentUrl
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
                                viewModel.navigateTo(url)
                                viewModel.closePanels()
                            },
                            onHistoryResumeClick = { folder ->
                                val item = history[folder] ?: return@HistoryPanel
                                if (item.nextUrl.isNotEmpty()) {
                                    val lastNum = item.chapter.filter { it.isDigit() }.toIntOrNull() ?: 0
                                    val nextConfig = item.config.copy(chapter = "@${lastNum + 1}")
                                    val presetName = if (item.presetName.isNotEmpty()) item.presetName else "(履歴から再開)"
                                    viewModel.applyPresetState(presetName, nextConfig)
                                    viewModel.startScraping(item.nextUrl, folder)
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
                                viewModel.navigateTo(url)
                                viewModel.closePanels()
                            },
                            onDeleteClick = { name -> viewModel.deleteFavorite(name) },
                            onCloseClick = { viewModel.closePanels() }
                        )
                    }
                    PanelType.TRANSLATION -> {
                        TranslationPanel(
                            uiState = uiState,
                            isV2Translating = v2EngineState.isRunning,
                            v2Content = { V2TranslationPanel(viewModel = v2ViewModel, onBrowseClick = { showRootMenu = true }) },
                            onSelectEngineTab = { engine -> viewModel.setActiveTranslationEngine(engine) },
                            onSelectFolderClick = { folderLauncher.launch(null) },
                            onBrowseClick = { showRootMenu = true },
                            onRemoveFolderClick = { engine, index -> viewModel.removeTranslationFolder(engine, index) },
                            onClearFoldersClick = { engine -> viewModel.clearTranslationFolders(engine) },
                            onUpdateDelays = { engine, chunkDelay, fileDelay -> viewModel.updateTranslationDelays(engine, chunkDelay, fileDelay) },
                            onStartTranslationClick = { engine -> viewModel.startTranslation(engine) },
                            onStopTranslationClick = { engine -> viewModel.stopTranslation(engine) },
                            onToggleWebSplit = { enabled -> viewModel.toggleWebSplit(enabled) },
                            onUpdateWebSplitSize = { sizeChars -> viewModel.updateWebSplitSizeChars(sizeChars) },
                            onUpdateInputEncoding = { value -> viewModel.updateInputEncoding(value) },
                            onOpenWebTranslateClick = { engine ->
                                val targetUrl = when (engine) {
                                    TranslationEngine.GOOGLE -> "https://translate.google.com/?sl=auto&tl=ja&op=translate"
                                    TranslationEngine.DEEPL -> "https://www.deepl.com/ja/translator#auto/ja/"
                                    TranslationEngine.PAPAGO -> "https://papago.naver.com/?sk=ko&tk=ja"
                                    TranslationEngine.LLM_API -> "https://aistudio.google.com/"
                                }
                                // 外部遷移要求は one-shot コマンドで一発配送する。
                                viewModel.navigateTo(targetUrl)
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
                is ActiveDialog.TextQuerySearch -> {
                    TextQuerySearchDialog(
                        onDismiss = { viewModel.dismissDialog() },
                        onSearch = { query, onResult ->
                            val js = ScrapingScriptBuilder.buildSearchTextScript(query)
                            webViewRef?.evaluateJavascript(js) { res ->
                                val list = if (res != null && res != "null" && res != "false") {
                                    try {
                                        val rawJson = Json.decodeFromString<String>(res)
                                        Json.decodeFromString<List<TextSearchResultItem>>(rawJson)
                                    } catch (e: Exception) {
                                        try {
                                            Json.decodeFromString<List<TextSearchResultItem>>(res)
                                        } catch (e2: Exception) {
                                            emptyList()
                                        }
                                    }
                                } else {
                                    emptyList()
                                }
                                // JS側エラー行や空セレクタは設定破壊防止のため除外
                                onResult(list.filter { it.selector.isNotBlank() && it.tag != "ERROR" })
                            }
                        },
                        onApplyToField = { field, selector ->
                            viewModel.applySelectorToConfig(field, selector)
                            Toast.makeText(context, "${field.displayName}に反映しました", Toast.LENGTH_SHORT).show()
                        },
                        onCopySelector = { selector ->
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("CSS Selector", selector))
                                Toast.makeText(context, "セレクタをコピーしました", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "コピー失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                ActiveDialog.None -> {}
            }

            // 自前ブラウザ: 許可根メニュー(フォルダ内選択と同一の器・同一サイズ)。
            if (showRootMenu) {
                PickerRootMenuDialog(
                    roots = grantedRoots,
                    health = rootHealth,
                    onOpen = { root ->
                        val bad = (rootHealth[root.treeUri]
                            ?: RootHealth.OK) != RootHealth.OK
                        if (bad) {
                            grantLauncher.launch(null)
                        } else {
                            browser = pickerVM.openRoot(root.treeUri, root.displayName)
                            showRootMenu = false
                        }
                    },
                    onForget = { pickerVM.forgetRoot(it.treeUri) },
                    onGrantNew = { grantLauncher.launch(null) },
                    onDismiss = { showRootMenu = false }
                )
            }

            // 自前ブラウザ: 選択画面(確定後は現行キューへ加算するのみ)。
            browser?.let { st ->
                SafBrowserDialog(
                    state = st,
                    onConfirm = { targets, result ->
                        val engine = uiState.activeTranslationEngine
                        if (engine == TranslationEngine.LLM_API) {
                            val v2targets = PickerTargetAdapter.toV2FolderTargets(targets)
                            v2ViewModel.addFolderTargets(v2targets)
                            val fileTotal = v2targets.sumOf { it.fileUris.size }
                            val msg = if (fileTotal > 0) {
                                "フォルダ${v2targets.size}件・選択${fileTotal}件を登録しました"
                            } else {
                                "フォルダ${v2targets.size}件を登録しました"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            val items = PickerTargetAdapter.toWebFolderItems(targets)
                            viewModel.addTranslationFolderItems(engine, items)
                            Toast.makeText(context, "${items.size}件を登録しました", Toast.LENGTH_SHORT).show()
                        }
                        if (result.truncated) {
                            Toast.makeText(context, "上限のため一部除外しました", Toast.LENGTH_LONG).show()
                        }
                        browser = null
                        pickerVM.closeBrowser()
                    },
                    onDismiss = {
                        browser = null
                        pickerVM.closeBrowser()
                    }
                )
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
    isV2Translating: Boolean,
    v2StatusText: String,
    currentStatusText: String
) {
    val translationStatus = when {
        isV2Translating ->
            "AI/LLM翻訳: $v2StatusText"
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