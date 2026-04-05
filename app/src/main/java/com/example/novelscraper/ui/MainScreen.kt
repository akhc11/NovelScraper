package com.example.novelscraper.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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

    BackHandler(enabled = uiState.openedPanel != PanelType.NONE || (webViewRef?.canGoBack() == true)) {
        if (uiState.openedPanel != PanelType.NONE) viewModel.closePanels()
        else if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
    }

    LaunchedEffect(uiState.currentUrl) {
        val view = webViewRef
        if (view != null && uiState.currentUrl.isNotEmpty() && view.url != uiState.currentUrl) {
            onNavigate(uiState.currentUrl, view)
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
                onStartScrapingClick = { onStartScraping(webViewRef?.url ?: uiState.inputUrl) },
                onTestRunClick = { webViewRef?.let { onTestRun(it) } },
                onToggleImagesClick = { viewModel.toggleBlockImages() },
                isDesktopMode = uiState.isDesktopMode
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            WebViewHelper.applyStandardSettings(this, !uiState.blockImages, uiState.isDesktopMode)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url != null && !url.startsWith("javascript:") && !url.startsWith("data:")) {
                                        viewModel.setCurrentUrl(url)
                                        viewModel.setInputUrl(url)
                                    }
                                }
                            }
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        view.settings.blockNetworkImage = uiState.blockImages
                        val targetUA = if (uiState.isDesktopMode) WebViewHelper.DESKTOP_UA else WebViewHelper.MOBILE_UA
                        if (view.settings.userAgentString != targetUA) {
                            view.settings.userAgentString = targetUA
                            view.reload()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = currentStatusText,
                    modifier = Modifier.fillMaxWidth().background(AppColors.backgroundMedium).padding(4.dp),
                    color = AppColors.textMuted,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            AnimatedVisibility(
                visible = uiState.openedPanel == PanelType.SETTINGS,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                SettingsPanel(
                    uiState = uiState, presets = presets,
                    onCloseClick = { viewModel.closePanels() },
                    onPresetSelected = { name, config -> viewModel.applyPresetState(name, config) },
                    onSavePresetClick = { onShowSavePreset() },
                    onDeletePresetClick = { viewModel.deletePreset(uiState.currentPresetName) },
                    onConfigChange = { viewModel.updateCurrentConfig { _ -> it } }
                )
            }
            
            AnimatedVisibility(
                visible = uiState.openedPanel == PanelType.HISTORY,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                HistoryPanel(
                    activeTab = uiState.activeHistoryTab,
                    onTabSelected = { viewModel.setActiveHistoryTab(it) },
                    activeTasks = activeTasks,
                    history = history,
                    onStopTaskClick = { task -> task.stop(); viewModel.removeTask(task) },
                    // サイトへ移動 (閲覧)
                    onHistoryItemClick = { url -> 
                        webViewRef?.let { onNavigate(url, it) }
                        viewModel.setCurrentUrl(url)
                        viewModel.setInputUrl(url)
                        viewModel.closePanels()
                    },
                    // 続きから再開
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

            AnimatedVisibility(
                visible = uiState.openedPanel == PanelType.FAVORITES,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
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
        }
    }
}