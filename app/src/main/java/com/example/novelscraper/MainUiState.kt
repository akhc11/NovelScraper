package com.example.novelscraper

import android.net.Uri

enum class PanelType { NONE, SETTINGS, HISTORY, FAVORITES, TRANSLATION }

data class MainUiState(
    val currentUrl: String = "",
    val inputUrl: String = "",
    val openedPanel: PanelType = PanelType.NONE,
    val isInspectMode: Boolean = false,
    val currentPresetName: String = "",
    val currentConfig: ScraperConfig = ScraperConfig(),
    val activeHistoryTab: Int = 0, // 0: History, 1: Active Tasks
    val blockImages: Boolean = false,
    val isDesktopMode: Boolean = false,
    // 翻訳関連の状態
    val translationFolderUri: Uri? = null,
    val translationFolderName: String = "",
    val isTranslating: Boolean = false,
    val translationStatusText: String = "待機中",
    val translationProgress: Pair<Int, Int> = Pair(0, 0), // (完了件数, 総件数)
    val translationCurrentFileName: String = "",
    val translationChunkProgress: Pair<Int, Int> = Pair(0, 0), // (現在チャンク, 総チャンク)
    val translationSourceLang: String = "auto",
    val translationTargetLang: String = "ja"
)
