package com.example.novelscraper

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novelscraper.scraper.*
import com.example.novelscraper.translation.web.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ScrapingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)
    private val presetRepository = PresetRepository(application)
    private val fileRepository = FileRepository(application)
    private val serviceController = ScraperServiceController(application)

    // 履歴・お気に入りの状態＋永続化はLibraryStoreに委譲（同一スコープ駆動）。
    private val libraryStore = LibraryStore(application, viewModelScope)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _presets = MutableStateFlow<Map<String, ScraperConfig>>(emptyMap())
    val presets: StateFlow<Map<String, ScraperConfig>> = _presets.asStateFlow()

    val favorites: StateFlow<Map<String, String>> get() = libraryStore.favorites

    val history: StateFlow<Map<String, HistoryItem>> get() = libraryStore.history

    private val taskList = mutableListOf<ScrapingTask>()
    private val _activeTasks = MutableStateFlow<List<ScrapingTask>>(emptyList())
    val activeTasks: StateFlow<List<ScrapingTask>> = _activeTasks.asStateFlow()

    private val _currentStatusText = MutableStateFlow("待機中")
    val currentStatusText: StateFlow<String> = _currentStatusText.asStateFlow()

    // UI層への一方向イベント（Toast等の表示はActivityが収集して描画する）。
    val eventBus = UiEventBus()

    private fun notify(message: String, long: Boolean = false) {
        viewModelScope.launch { eventBus.send(UiEvent.ShowToast(message, isLong = long)) }
    }

    // autoUrl の自動適用管理（同一サイト巡回中や検索・ホーム離脱時に、ユーザーの手動編集設定が勝手に上書きされるのを防止）
    private var lastAppliedAutoUrlDomain: String = ""
    private var isConfigManuallyEdited: Boolean = false

    // 翻訳キューマネージャー
    val translationManager = TranslationQueueManager(application, viewModelScope, repository)

    init {
        viewModelScope.launch {
            presetRepository.presetsFlow.collect { _presets.value = it }
        }
        viewModelScope.launch {
            repository.webViewDarkModeFlow.collect { isDark ->
                _uiState.update { it.copy(isWebViewDarkMode = isDark) }
            }
        }

        // 翻訳状態の購読とUIStateへの反映
        viewModelScope.launch {
            translationManager.googleState.collect { gs ->
                _uiState.update { it.copy(googleTranslationState = gs) }
            }
        }
        viewModelScope.launch {
            translationManager.deeplState.collect { ds ->
                _uiState.update { it.copy(deeplTranslationState = ds) }
            }
        }
        viewModelScope.launch {
            translationManager.papagoState.collect { ps ->
                _uiState.update { it.copy(papagoTranslationState = ps) }
            }
        }
        viewModelScope.launch {
            translationManager.isWebSplitEnabled.collect { enabled ->
                _uiState.update { it.copy(isWebSplitEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            translationManager.webSplitSizeChars.collect { sizeChars ->
                _uiState.update { it.copy(webSplitSizeChars = sizeChars) }
            }
        }
        viewModelScope.launch {
            translationManager.inputEncoding.collect { encoding ->
                _uiState.update { it.copy(inputEncoding = encoding?.name ?: "AUTO") }
            }
        }
        translationManager.onActivityChanged = { syncServiceStatus() }

        viewModelScope.launch {
            repository.setupDoneFlow.collect { done ->
                if (!done) {
                    val initialPresets = mutableMapOf<String, ScraperConfig>()
                    initialPresets["初期設定(小説家になろう)"] = ScraperConfig(
                        body = "#novel_honbun",
                        title = ".novel_subtitle",
                        delay = "5-10",
                        endCheck = "list|index|toc|javascript|null",
                        autoUrl = "syosetu.com"
                    )
                    presetRepository.savePresets(initialPresets)
                    repository.saveSetupDone(true)
                }
            }
        }
    }

    private fun refreshStatus() {
        _activeTasks.value = taskList.toList()
        _currentStatusText.value = taskList.lastOrNull()?.status ?: if (taskList.isNotEmpty()) "実行中: ${taskList.size}件" else "待機中"
        syncServiceStatus()
    }

    // ---- ダイアログ状態管理（Compose駆動） ----

    fun showAddFavoriteDialog(title: String, url: String) {
        _uiState.update { it.copy(activeDialog = ActiveDialog.AddFavorite(title, url)) }
    }

    fun showSavePresetDialog(defaultName: String, currentUrl: String) {
        _uiState.update { it.copy(activeDialog = ActiveDialog.SavePreset(defaultName, currentUrl)) }
    }

    fun showTextQuerySearchDialog() {
        _uiState.update { it.copy(activeDialog = ActiveDialog.TextQuerySearch) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = ActiveDialog.None) }
    }

    // ---- パネル管理 ----

    fun togglePanel(panel: PanelType) {
        _uiState.update {
            val current = (it.overlay as? Overlay.Panel)?.type
            val newOverlay = if (current == panel) Overlay.None else Overlay.Panel(panel)
            it.copy(overlay = newOverlay)
        }
    }

    fun closePanels() {
        _uiState.update { it.copy(overlay = Overlay.None) }
    }

    // ---- オーバーレイ管理 ----

    fun showTestResultOverlay(data: ScrapingResult) {
        _uiState.update { it.copy(overlay = Overlay.TestResult(data)) }
    }

    fun setTestResult(data: ScrapingResult?) {
        _uiState.update { it.copy(overlay = if (data != null) Overlay.TestResult(data) else Overlay.None) }
    }

    fun setInspectMode(active: Boolean) {
        _uiState.update { it.copy(overlay = if (active) Overlay.InspectMode else Overlay.None) }
    }

    fun closeOverlay() {
        _uiState.update { it.copy(overlay = Overlay.None) }
    }

    fun setExcludeCandidates(state: ExcludeCandidatesState?) {
        _uiState.update {
            val ov = it.overlay
            if (ov is Overlay.TestResult) it.copy(overlay = ov.copy(excludeCandidates = state)) else it
        }
    }

    fun addExcludeSelector(selector: String) {
        if (selector.isEmpty()) return
        updateCurrentConfig { old -> old.copy(exclude = ExcludeSelectorCodec.merge(old.exclude, selector)) }
    }

    fun removeExcludeSelector(selector: String) {
        if (selector.isEmpty()) return
        updateCurrentConfig { old -> old.copy(exclude = ExcludeSelectorCodec.remove(old.exclude, selector)) }
    }

    fun applySelectorToConfig(field: SelectorField, selector: String) {
        updateCurrentConfig { old ->
            when (field) {
                SelectorField.TITLE -> old.copy(title = selector)
                SelectorField.BODY -> old.copy(body = selector)
                SelectorField.NEXT -> old.copy(next = selector)
                SelectorField.CHAPTER -> old.copy(chapter = selector)
                SelectorField.FOLDER -> old.copy(folder = selector)
                SelectorField.FOLDER_LINK -> old.copy(folderLink = selector)
                SelectorField.EXCLUDE -> old.copy(exclude = ExcludeSelectorCodec.merge(old.exclude, selector))
            }
        }
    }

    fun addTask(task: ScrapingTask) { taskList.add(task); refreshStatus() }
    fun removeTask(task: ScrapingTask) { taskList.remove(task); refreshStatus() }
    fun updateStatus() { refreshStatus() }

    fun savePreset(name: String, config: ScraperConfig) {
        lastAppliedAutoUrlDomain = config.autoUrl
        isConfigManuallyEdited = false
        viewModelScope.launch { presetRepository.updatePresets { it[name] = config } }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            presetRepository.updatePresets { it.remove(name) }
            if (_uiState.value.currentPresetName == name) {
                lastAppliedAutoUrlDomain = ""
                isConfigManuallyEdited = false
                _uiState.update { it.copy(currentPresetName = "", currentConfig = ScraperConfig()) }
            }
        }
    }

    fun saveFavorite(title: String, url: String) {
        libraryStore.saveFavorite(title, url)
    }

    fun deleteFavorite(title: String) {
        libraryStore.deleteFavorite(title)
    }

    fun updateHistory(folderName: String, title: String, chapter: String, url: String, nextUrl: String, config: ScraperConfig) {
        libraryStore.updateHistory(
            folderName, title, chapter, url, nextUrl, config,
            presetName = _uiState.value.currentPresetName
        )
    }

    fun deleteHistory(folderName: String) {
        libraryStore.deleteHistory(folderName)
    }

    fun setInputUrl(url: String) { _uiState.update { it.copy(inputUrl = url) } }

    fun setCurrentUrl(url: String) {
        // 同一URLの重複更新を抑止（WebViewClientの3コールバックが同URLで連呼するため）。
        // ナビゲーション発火（LaunchedEffect）は正規化比較で同一URLを無視するので、ここでの早期復帰と整合する。
        if (_uiState.value.currentUrl == url) return
        _uiState.update { 
            it.copy(
                currentUrl = url, 
                inputUrl = url,
                isLiveTranslating = false,
                isLiveTranslated = false
            ) 
        }
        viewModelScope.launch {
            val presetsMap = _presets.value
            var matchedPreset: Pair<String, ScraperConfig>? = null
            for ((name, config) in presetsMap) {
                if (config.autoUrl.isNotEmpty() && url.contains(config.autoUrl)) {
                    matchedPreset = Pair(name, config)
                    break
                }
            }

            if (matchedPreset != null) {
                val (name, config) = matchedPreset
                // 1. 全く異なる小説サイト（別autoUrlドメイン）へ移動した時のみ、そのサイトのプリセットを自動適用
                if (lastAppliedAutoUrlDomain != config.autoUrl) {
                    lastAppliedAutoUrlDomain = config.autoUrl
                    isConfigManuallyEdited = false
                    _uiState.update { it.copy(currentPresetName = name, currentConfig = config) }
                } else {
                    // 2. 同一サイト内の巡回中:
                    // ユーザーが手動編集していない場合のみ、万が一未適用の初期プリセットがあれば同期
                    if (!isConfigManuallyEdited && _uiState.value.currentPresetName != name) {
                        _uiState.update { it.copy(currentPresetName = name, currentConfig = config) }
                    }
                }
            }
            // 3. マッチするプリセットがない場合（ホーム画面・Google検索などへの一時的移動）:
            // lastAppliedAutoUrlDomain や currentConfig、手動編集状態は一切リセットせず100%保護する
        }
    }

    fun setBlockImages(block: Boolean) { _uiState.update { it.copy(blockImages = block) } }
    fun setDesktopMode(isDesktop: Boolean) { _uiState.update { it.copy(isDesktopMode = isDesktop) } }
    fun setWebViewDarkMode(isDark: Boolean) {
        _uiState.update { it.copy(isWebViewDarkMode = isDark) }
        viewModelScope.launch { repository.saveWebViewDarkMode(isDark) }
    }
    fun toggleWebViewDarkMode() {
        setWebViewDarkMode(!_uiState.value.isWebViewDarkMode)
    }

    fun setActiveHistoryTab(tab: Int) {
        _uiState.update { it.copy(activeHistoryTab = tab) }
    }

    fun setActiveTranslationEngine(engine: TranslationEngine) {
        _uiState.update { it.copy(activeTranslationEngine = engine) }
    }

    // ---- ライブ翻訳（Webページ即時翻訳）の状態ハンドリング ----
    fun handleLiveTranslateStatus(status: String) {
        when {
            status == "START" -> {
                _uiState.update { it.copy(isLiveTranslating = true) }
            }
            status == "SUCCESS" -> {
                _uiState.update { it.copy(isLiveTranslating = false, isLiveTranslated = true) }
            }
            status == "RESTORED" -> {
                _uiState.update { it.copy(isLiveTranslating = false, isLiveTranslated = false) }
            }
            status.startsWith("ERROR:") -> {
                val errorMsg = status.removePrefix("ERROR:").trim()
                _uiState.update { it.copy(isLiveTranslating = false) }
                notify("ライブ翻訳エラー: $errorMsg", long = true)
            }
        }
    }

    fun applyPreset(name: String) {
        val config = _presets.value[name] ?: return
        lastAppliedAutoUrlDomain = config.autoUrl
        isConfigManuallyEdited = false
        _uiState.update { it.copy(currentPresetName = name, currentConfig = config) }
    }

    fun applyPresetState(name: String, config: ScraperConfig) {
        lastAppliedAutoUrlDomain = config.autoUrl
        isConfigManuallyEdited = false
        _uiState.update { it.copy(currentPresetName = name, currentConfig = config) }
    }

    fun clearPreset() {
        lastAppliedAutoUrlDomain = ""
        isConfigManuallyEdited = false
        _uiState.update { it.copy(currentPresetName = "", currentConfig = ScraperConfig()) }
    }

    fun updateCurrentConfig(transform: (ScraperConfig) -> ScraperConfig) {
        isConfigManuallyEdited = true
        _uiState.update {
            val newConfig = transform(it.currentConfig)
            val updatedPresetName = if (it.currentPresetName.isNotEmpty()) {
                val origConfig = _presets.value[it.currentPresetName]
                if (origConfig != null && origConfig != newConfig) "" else it.currentPresetName
            } else ""
            it.copy(currentConfig = newConfig, currentPresetName = updatedPresetName)
        }
    }

    fun selectPresetFromList(name: String, config: ScraperConfig) {
        lastAppliedAutoUrlDomain = config.autoUrl
        isConfigManuallyEdited = false
        _uiState.update {
            it.copy(
                currentPresetName = name,
                currentConfig = config,
                overlay = Overlay.None
            )
        }
    }

    // ---- フォルダ翻訳機能 ----

    fun addTranslationFolder(engine: TranslationEngine, uri: Uri, folderName: String) {
        translationManager.addFolder(engine, uri, folderName)
    }

    fun removeTranslationFolder(engine: TranslationEngine, index: Int) {
        translationManager.removeFolder(engine, index)
    }

    fun clearTranslationFolders(engine: TranslationEngine) {
        translationManager.clearFolders(engine)
    }

    fun toggleWebSplit(enabled: Boolean) {
        translationManager.toggleWebSplit(enabled)
    }

    fun updateWebSplitSizeChars(sizeChars: Int) {
        translationManager.updateWebSplitSizeChars(sizeChars)
    }

    fun updateInputEncoding(value: String) {
        translationManager.updateInputEncoding(value)
    }

    fun updateTranslationDelays(engine: TranslationEngine, chunkDelay: String, fileDelay: String) {
        translationManager.updateDelays(engine, chunkDelay, fileDelay)
    }

    fun startTranslation(engine: TranslationEngine) {
        translationManager.start(engine)
    }

    fun stopTranslation(engine: TranslationEngine) {
        translationManager.stop(engine)
    }

    private fun syncServiceStatus() {
        val scrapingCount = taskList.size
        val googleState = translationManager.googleState.value
        val deeplState = translationManager.deeplState.value
        val papagoState = translationManager.papagoState.value
        val isTranslating = googleState.isTranslating || deeplState.isTranslating || papagoState.isTranslating

        if (scrapingCount > 0 || isTranslating) {
            val statusParts = mutableListOf<String>()
            if (scrapingCount > 0) {
                statusParts.add("スクレイプト: ${scrapingCount}件")
            }
            if (googleState.isTranslating) {
                val gPrefix = if (googleState.selectedFolders.size > 1) "[${googleState.currentFolderIndex + 1}/${googleState.selectedFolders.size}] " else ""
                statusParts.add("Google: ${gPrefix}${googleState.progress.first}/${googleState.progress.second}件")
            }
            if (deeplState.isTranslating) {
                val dPrefix = if (deeplState.selectedFolders.size > 1) "[${deeplState.currentFolderIndex + 1}/${deeplState.selectedFolders.size}] " else ""
                statusParts.add("DeepL: ${dPrefix}${deeplState.progress.first}/${deeplState.progress.second}件")
            }
            if (papagoState.isTranslating) {
                val pPrefix = if (papagoState.selectedFolders.size > 1) "[${papagoState.currentFolderIndex + 1}/${papagoState.selectedFolders.size}] " else ""
                statusParts.add("Papago: ${pPrefix}${papagoState.progress.first}/${papagoState.progress.second}件")
            }
            val msg = if (statusParts.isNotEmpty()) {
                statusParts.joinToString(" / ")
            } else {
                "処理中..."
            }
            serviceController.updateNotification(msg)
        } else {
            serviceController.stopService()
        }
    }

    fun startScraping(targetUrl: String, initialFolderName: String = "(取得中...)") {
        // currentUrlは巡回で進むため開始URLでも照合する（2話目以降の重複起動・停止失敗を防ぐ）。
        val isAlreadyRunning = taskList.any { task ->
            task.isRunning && (
                task.currentUrl == targetUrl ||
                task.startUrl == targetUrl ||
                (initialFolderName.isNotEmpty() && initialFolderName != "(取得中...)" && task.folderName == initialFolderName)
            )
        }
        if (isAlreadyRunning) {
            notify("既にスクレイピング実行中です")
            return
        }

        WebViewHelper.clearGoogleTranslateCookies(targetUrl)
        val config = uiState.value.currentConfig
        val newTask = ScrapingTask(
            getApplication(), targetUrl, config,
            !uiState.value.blockImages,
            uiState.value.isDesktopMode,
            initialFolderName,
            object : ScrapingTask.TaskListener {
                override fun onStatusUpdate(task: ScrapingTask, status: String) {
                    updateStatus()
                }
                override fun onTaskFinished(task: ScrapingTask) {
                    task.stop()
                    removeTask(task)
                }
                override fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String) {
                    viewModelScope.launch {
                        val success = fileRepository.saveChapter(folderName, title, content, chapterNum)
                        if (!success) {
                            notify("ファイル保存に失敗しました: $title ($chapterNum)")
                        }
                    }
                }
                override fun onUpdateHistory(folderName: String, title: String, chapter: String, url: String, nextUrl: String, config: ScraperConfig) {
                    updateHistory(folderName, title, chapter, url, nextUrl, config)
                }
            }
        )
        addTask(newTask)
        newTask.start()
        closePanels()
    }

    fun exportPresets(uri: Uri) {
        viewModelScope.launch {
            val result = presetRepository.exportPresets(uri)
            val msg = if (result.isSuccess) {
                "プリセットをエクスポートしました"
            } else {
                "エクスポートに失敗しました: ${result.exceptionOrNull()?.message ?: "不明なエラー"}"
            }
            notify(msg, long = true)
        }
    }

    fun importPresets(uri: Uri) {
        viewModelScope.launch {
            val result = presetRepository.importPresets(uri)
            val msg = if (result.isSuccess) {
                "${result.getOrNull()} 件のプリセットをインポートしました"
            } else {
                val ex = result.exceptionOrNull()
                val errorDetails = when (ex) {
                    is kotlinx.serialization.SerializationException -> "無効なファイル形式です"
                    is IllegalArgumentException -> ex.message ?: "無効なデータです"
                    else -> ex?.message ?: "不明なエラー"
                }
                "インポートに失敗しました: $errorDetails"
            }
            notify(msg, long = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        taskList.forEach { it.stop() }
        taskList.clear()
        translationManager.shutdown()
    }
}