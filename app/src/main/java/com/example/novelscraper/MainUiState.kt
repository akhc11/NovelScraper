package com.example.novelscraper

enum class PanelType {
    NONE, SETTINGS, HISTORY, FAVORITES
}

data class MainUiState(
    val currentUrl: String = "",
    val inputUrl: String = "",
    val openedPanel: PanelType = PanelType.NONE,
    val isInspectMode: Boolean = false,
    val blockImages: Boolean = true,
    val currentPresetName: String = "",
    val activeHistoryTab: Int = 0, // 0: 実行中, 1: 履歴
    val currentConfig: ScraperConfig = ScraperConfig()
)
