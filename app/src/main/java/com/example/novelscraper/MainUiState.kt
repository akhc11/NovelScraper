package com.example.novelscraper

import android.net.Uri
import kotlinx.serialization.Serializable

enum class PanelType { SETTINGS, HISTORY, FAVORITES, TRANSLATION }

/**
 * オーバーレイ状態（単一排他を型で保証）。
 * 同時に1つしか開けない: パネル / テスト結果(+除外候補カードはその子) / インスペクター。
 */
sealed interface Overlay {
    data object None : Overlay
    data class Panel(val type: PanelType) : Overlay
    data class TestResult(
        val result: ScrapingResult,
        val excludeCandidates: ExcludeCandidatesState? = null
    ) : Overlay
    data object InspectMode : Overlay
}

/**
 * 表示中のダイアログ状態（Jetpack Compose 駆動）。
 */
sealed interface ActiveDialog {
    data object None : ActiveDialog
    data class AddFavorite(val title: String, val url: String) : ActiveDialog
    data class SavePreset(val defaultName: String, val currentUrl: String) : ActiveDialog
    data class InspectElement(val selector: String) : ActiveDialog
}

enum class TranslationEngine { GOOGLE, DEEPL }

@Serializable
data class ExcludeCandidate(
    val label: String,
    val selector: String,
    val metric: String,
    val preview: String
)

data class ExcludeCandidatesState(
    val baseSelector: String,
    val items: List<ExcludeCandidate>
)

data class EngineTranslationState(
    val folderUri: Uri? = null,
    val folderName: String = "",
    val selectedFolders: List<FolderItem> = emptyList(),
    val currentFolderIndex: Int = 0,
    val isTranslating: Boolean = false,
    val statusText: String = "待機中",
    val progress: Pair<Int, Int> = Pair(0, 0), // (完了件数, 総件数)
    val currentFileName: String = "",
    val chunkProgress: Pair<Int, Int> = Pair(0, 0), // (現在チャンク, 総チャンク)
    val sourceLang: String = "auto",
    val targetLang: String = "ja",
    val chunkDelay: String = "1-3",
    val fileDelay: String = "1-2"
)

data class MainUiState(
    val currentUrl: String = "",
    val inputUrl: String = "",
    val overlay: Overlay = Overlay.None,
    val activeDialog: ActiveDialog = ActiveDialog.None,
    val currentPresetName: String = "",
    val currentConfig: ScraperConfig = ScraperConfig(),
    val activeHistoryTab: Int = 0, // 0: History, 1: Active Tasks
    val blockImages: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isWebViewDarkMode: Boolean = true,

    // Webサイト即時翻訳 (インプレースDOM翻訳) 状態
    val isLiveTranslating: Boolean = false,
    val isLiveTranslated: Boolean = false,
    
    // 翻訳関連の状態（エンジンごとに独立管理）
    val activeTranslationEngine: TranslationEngine = TranslationEngine.GOOGLE,
    val googleTranslationState: EngineTranslationState = EngineTranslationState(chunkDelay = "1-3", fileDelay = "1-2"),
    val deeplTranslationState: EngineTranslationState = EngineTranslationState(chunkDelay = "3-8", fileDelay = "2-5")
) {
    /** インスペクター有効か（既存参照互換の派生プロパティ） */
    val isInspectMode: Boolean
        get() = overlay is Overlay.InspectMode

    /** 現在開いているパネル（なければnull・既存参照互換の派生プロパティ） */
    val activePanelType: PanelType?
        get() = (overlay as? Overlay.Panel)?.type

    /** 現在選択中のタブの翻訳エンジン状態 */
    val currentEngineState: EngineTranslationState
        get() = when (activeTranslationEngine) {
            TranslationEngine.GOOGLE -> googleTranslationState
            TranslationEngine.DEEPL -> deeplTranslationState
        }

    /** いずれかのエンジンが翻訳中かどうか */
    val isAnyTranslating: Boolean
        get() = googleTranslationState.isTranslating || deeplTranslationState.isTranslating
}