package com.example.novelscraper

enum class PanelType { NONE, SETTINGS, HISTORY, FAVORITES }

data class MainUiState(
    val currentUrl: String = "",
    val inputUrl: String = "",
    val openedPanel: PanelType = PanelType.NONE,
    val isInspectMode: Boolean = false,
    val currentPresetName: String = "",
    val currentConfig: ScraperConfig = ScraperConfig(),
    val activeHistoryTab: Int = 0, // 0: History, 1: Active Tasks
    val blockImages: Boolean = false,
    val isDesktopMode: Boolean = false
)
