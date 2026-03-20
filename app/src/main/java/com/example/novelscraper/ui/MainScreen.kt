package com.example.novelscraper.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.novelscraper.*
import com.example.novelscraper.ui.components.*

@Composable
fun MainScreen(
    viewModel: ScrapingViewModel,
    onStartScraping: (String) -> Unit,
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

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Handle system back button
    BackHandler(enabled = uiState.openedPanel != PanelType.NONE || (webViewRef?.canGoBack() == true)) {
        if (uiState.openedPanel != PanelType.NONE) {
            viewModel.closePanels()
        } else if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        }
    }

    // Safely trigger URL load when currentUrl changes via ViewModel
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
                onStarClick = { 
                    webViewRef?.let { onShowAddFavorite(it.title ?: "", it.url ?: "") } 
                },
                onUrlSubmit = { url -> 
                    webViewRef?.let { onNavigate(url, it) }
                    viewModel.setInputUrl(url)
                    viewModel.closePanels() // URL検索時に開いているすべてのパネルを閉じる
                },
                onUrlChange = { viewModel.setInputUrl(it) },
                onPanelToggle = { viewModel.setOpenedPanel(it) },
                onInspectModeToggle = {
                    viewModel.toggleInspectMode()
                    if (!uiState.isInspectMode) {
                        webViewRef?.let { onInjectInspector(it) }
                    } else {
                        webViewRef?.reload()
                    }
                },
                onInspectToolClick = { webViewRef?.let { onLaunchAnalysisTool(it) } }
            )
        },
        floatingActionButton = {
            if (uiState.openedPanel == PanelType.NONE) {
                FloatingActionButton(
                    onClick = { onStartScraping(webViewRef?.url ?: uiState.inputUrl) },
                    containerColor = Color(0xFFFF5722),
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Start")
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            // 1. WebView (Main Content)
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            WebViewHelper.applyStandardSettings(this, !uiState.blockImages)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let {
                                        viewModel.setCurrentUrl(it)
                                        viewModel.setInputUrl(it)
                                    }
                                    if (uiState.isInspectMode) view?.let { onInjectInspector(it) }
                                }
                            }
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        view.settings.blockNetworkImage = uiState.blockImages
                    },
                    modifier = Modifier.weight(1f)
                )

                // Status bar at bottom
                Text(
                    text = activeTasks.lastOrNull()?.status ?: if (activeTasks.isNotEmpty()) "実行中: ${activeTasks.size}件" else "待機中",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF222222))
                        .padding(4.dp),
                    color = Color(0xFFCCCCCC),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            // 2. Overlay Panels
            AnimatedVisibility(
                visible = uiState.openedPanel == PanelType.SETTINGS,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.fillMaxSize()
            ) {
                SettingsPanel(
                    uiState = uiState,
                    presets = presets,
                    onCloseClick = { viewModel.closePanels() },
                    onTestRunClick = { webViewRef?.let { onTestRun(it) } },
                    onToggleImagesClick = { viewModel.toggleBlockImages() },
                    onPresetSelected = { name, config -> viewModel.applyPresetState(name, config) },
                    onSavePresetClick = { onShowSavePreset() },
                    onDeletePresetClick = { viewModel.deletePreset(uiState.currentPresetName) },
                    onConfigChange = { viewModel.updateCurrentConfig { _ -> it } }
                )
            }

            AnimatedVisibility(
                visible = uiState.openedPanel == PanelType.HISTORY,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.fillMaxSize()
            ) {
                HistoryPanel(
                    activeTab = uiState.activeHistoryTab,
                    onTabSelected = { viewModel.setActiveHistoryTab(it) },
                    activeTasks = activeTasks,
                    history = history,
                    onStopTaskClick = { task -> 
                        task.stop()
                        viewModel.removeTask(task)
                    },
                    onHistoryItemClick = { url -> 
                        viewModel.setCurrentUrl(url)
                        viewModel.closePanels()
                    },
                    onDeleteHistoryClick = { folder -> viewModel.deleteHistory(folder) }
                )
            }

            AnimatedVisibility(
                visible = uiState.openedPanel == PanelType.FAVORITES,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.fillMaxSize()
            ) {
                FavoritesPanel(
                    favorites = favorites,
                    onFavoriteClick = { url -> 
                        viewModel.setCurrentUrl(url)
                        viewModel.closePanels()
                    },
                    onDeleteClick = { name -> viewModel.deleteFavorite(name) },
                    onCloseClick = { viewModel.closePanels() }
                )
            }
        }
    }
}
