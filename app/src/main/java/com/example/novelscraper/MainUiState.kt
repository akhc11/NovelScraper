package com.example.novelscraper

import android.net.Uri
import com.example.novelscraper.translation.llm.engine.LlmEngineState
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
    data object LlmSettings : ActiveDialog
}

/**
 * One-shot UI イベント（Toast 表示など、画面のライフサイクルと同期した一時的通知）。
 */
sealed interface UiEvent {
    data class ShowToast(val message: String, val isLong: Boolean = false) : UiEvent
    data class NavigateToUrl(val url: String) : UiEvent
}

enum class TranslationEngine { GOOGLE, DEEPL, LLM_API }

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

/**
 * アプリ全体の完全一元化 UI 状態 (Single Source of Truth)。
 */
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

    // 一元化されたプリセット・お気に入り・履歴・タスクリスト
    val presets: Map<String, ScraperConfig> = emptyMap(),
    val favorites: Map<String, String> = emptyMap(),
    val history: Map<String, HistoryItem> = emptyMap(),
    val activeTasks: List<ScrapingTask> = emptyList(),
    val currentStatusText: String = "待機中",

    // Webサイト即時翻訳 (インプレースDOM翻訳) 状態
    val isLiveTranslating: Boolean = false,
    val isLiveTranslated: Boolean = false,
    
    // 翻訳関連の状態（エンジンごとに独立管理）
    val activeTranslationEngine: TranslationEngine = TranslationEngine.GOOGLE,
    val googleTranslationState: EngineTranslationState = EngineTranslationState(chunkDelay = "1-3", fileDelay = "1-2"),
    val deeplTranslationState: EngineTranslationState = EngineTranslationState(chunkDelay = "3-8", fileDelay = "2-5"),
    val llmTranslationState: EngineTranslationState = EngineTranslationState(chunkDelay = "2", fileDelay = "2"),
    val llmEngineLiveState: LlmEngineState = LlmEngineState()
) {
    val isInspectMode: Boolean
        get() = overlay is Overlay.InspectMode

    val activePanelType: PanelType?
        get() = (overlay as? Overlay.Panel)?.type

    val currentEngineState: EngineTranslationState
        get() = when (activeTranslationEngine) {
            TranslationEngine.GOOGLE -> googleTranslationState
            TranslationEngine.DEEPL -> deeplTranslationState
            TranslationEngine.LLM_API -> llmTranslationState
        }

    val isAnyTranslating: Boolean
        get() = googleTranslationState.isTranslating || deeplTranslationState.isTranslating || llmEngineLiveState.isTranslating
}