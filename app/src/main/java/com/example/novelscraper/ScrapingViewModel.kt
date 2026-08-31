package com.example.novelscraper

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class ScrapingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)
    private val fileRepository = FileRepository(application)
    private val serviceController = ScraperServiceController(application)

    private val taskList = CopyOnWriteArrayList<ScrapingTask>()
    private val _activeTasks = MutableStateFlow<List<ScrapingTask>>(emptyList())
    val activeTasks: StateFlow<List<ScrapingTask>> = _activeTasks.asStateFlow()

    private val _currentStatusText = MutableStateFlow("待機中")
    val currentStatusText: StateFlow<String> = _currentStatusText.asStateFlow()

    private val _presets = MutableStateFlow<Map<String, ScraperConfig>>(emptyMap())
    val presets: StateFlow<Map<String, ScraperConfig>> = _presets.asStateFlow()

    private val _favorites = MutableStateFlow<Map<String, String>>(emptyMap())
    val favorites: StateFlow<Map<String, String>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<Map<String, HistoryItem>>(emptyMap())
    val history: StateFlow<Map<String, HistoryItem>> = _history.asStateFlow()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // 翻訳キュー管理（Google/DeepLの実行ロジックは専任クラスへ分離。状態はcollectで合成）
    private val translationManager = TranslationQueueManager(
        appContext = application,
        scope = viewModelScope,
        repository = repository
    )

    init {
        // 過去のバグで保存されてしまった不正な履歴を起動時にクリーンアップ
        viewModelScope.launch {
            repository.updateHistory { historyMap ->
                val invalidKeys = historyMap.filter { (_, item) ->
                    item.url.startsWith("javascript:") || item.url.startsWith("data:") || item.url.length > 2000
                }.keys
                invalidKeys.forEach { historyMap.remove(it) }
            }
        }

        viewModelScope.launch { repository.presetsFlow.collect { _presets.value = it } }
        viewModelScope.launch { repository.favoritesFlow.collect { _favorites.value = it } }
        viewModelScope.launch { repository.historyFlow.collect { _history.value = it } }
        viewModelScope.launch {
            repository.webViewDarkModeFlow.collect { isDark ->
                _uiState.update { it.copy(isWebViewDarkMode = isDark) }
            }
        }

        // 翻訳キュー状態の合成（Manager の StateFlow → MainUiState）
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
                    repository.savePresets(initialPresets)
                    repository.saveSetupDone(true)
                }
            }
        }
    }

    private fun refreshStatus() {
        _activeTasks.value = taskList.toList()
        _currentStatusText.value = taskList.lastOrNull()?.status ?: if (taskList.isNotEmpty()) "実行中: 件" else "待機中"
        syncServiceStatus()
    }

    // ---- ダイアログ状態管理（Compose駆動） ----

    fun showAddFavoriteDialog(title: String, url: String) {
        _uiState.update { it.copy(activeDialog = ActiveDialog.AddFavorite(title, url)) }
    }

    fun showSavePresetDialog(defaultName: String, currentUrl: String) {
        _uiState.update { it.copy(activeDialog = ActiveDialog.SavePreset(defaultName, currentUrl)) }
    }

    fun showInspectElementDialog(selector: String) {
        _uiState.update { it.copy(activeDialog = ActiveDialog.InspectElement(selector)) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = ActiveDialog.None) }
    }

    fun setTestResult(result: ScrapingResult?) {
        _uiState.update {
            when {
                // 結果を開く場合は他のオーバーレイを閉じて TestResult へ
                result != null -> it.copy(overlay = Overlay.TestResult(result))
                // nullは「閉じる」: テスト結果表示中のみ閉じる（他オーバーレイは保持）
                it.overlay is Overlay.TestResult -> it.copy(overlay = Overlay.None)
                else -> it
            }
        }
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

    fun addTask(task: ScrapingTask) { taskList.add(task); refreshStatus() }
    fun removeTask(task: ScrapingTask) { taskList.remove(task); refreshStatus() }
    fun updateStatus() { refreshStatus() }

    fun savePreset(name: String, config: ScraperConfig) {
        viewModelScope.launch { repository.updatePresets { it[name] = config } }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            repository.updatePresets { it.remove(name) }
            if (_uiState.value.currentPresetName == name) {
                _uiState.update { it.copy(currentPresetName = "", currentConfig = ScraperConfig()) }
            }
        }
    }

    fun saveFavorite(title: String, url: String) {
        viewModelScope.launch { repository.updateFavorites { it[title] = url } }
    }

    fun deleteFavorite(title: String) {
        viewModelScope.launch { repository.updateFavorites { it.remove(title) } }
    }

    fun updateHistory(folderName: String, title: String, chapter: String, url: String, nextUrl: String, config: ScraperConfig) {
        if (url.startsWith("javascript:") || url.startsWith("data:") || url.length > 2000) return
        val safeFolderName = folderName.ifEmpty { "(不明な作品)" }
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        val item = HistoryItem(
            title = title,
            chapter = chapter,
            url = url,
            config = config,
            time = dateStr,
            presetName = _uiState.value.currentPresetName,
            timestamp = System.currentTimeMillis(),
            nextUrl = nextUrl
        )
        viewModelScope.launch {
            repository.updateHistory { historyMap ->
                historyMap[safeFolderName] = item
            }
        }
    }

    fun deleteHistory(folderName: String) {
        viewModelScope.launch { repository.updateHistory { it.remove(folderName) } }
    }

    fun setInputUrl(url: String) { _uiState.update { it.copy(inputUrl = url) } }

    fun setCurrentUrl(url: String) {
        _uiState.update { it.copy(currentUrl = url, inputUrl = url, isWebPageTranslated = false) }
        viewModelScope.launch {
            val presetsMap = _presets.value
            for ((name, config) in presetsMap) {
                if (config.autoUrl.isNotEmpty() && url.contains(config.autoUrl)) {
                    _uiState.update { it.copy(currentPresetName = name, currentConfig = config) }
                    break
                }
            }
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

    fun setWebPageTranslated(translated: Boolean) {
        _uiState.update { it.copy(isWebPageTranslated = translated) }
    }

    fun toggleWebPageTranslation() {
        _uiState.update { it.copy(isWebPageTranslated = !it.isWebPageTranslated) }
    }

    fun togglePanel(panel: PanelType) {
        _uiState.update {
            val current = it.overlay as? Overlay.Panel
            it.copy(overlay = if (current?.type == panel) Overlay.None else Overlay.Panel(panel))
        }
    }

    fun closePanels() {
        _uiState.update { it.copy(overlay = Overlay.None) }
    }

    fun setInspectMode(active: Boolean) {
        _uiState.update {
            when {
                active -> it.copy(overlay = Overlay.InspectMode)
                it.overlay is Overlay.InspectMode -> it.copy(overlay = Overlay.None)
                else -> it
            }
        }
    }

    fun applySelectorToConfig(field: SelectorField, selector: String) {
        _uiState.update { state ->
            val updated = when (field) {
                SelectorField.BODY -> state.currentConfig.copy(body = selector)
                SelectorField.TITLE -> state.currentConfig.copy(title = selector)
                SelectorField.NEXT -> state.currentConfig.copy(next = selector)
                SelectorField.CHAPTER -> state.currentConfig.copy(chapter = selector)
                SelectorField.FOLDER -> state.currentConfig.copy(folder = selector)
                SelectorField.FOLDER_LINK -> state.currentConfig.copy(folderLink = selector)
                SelectorField.EXCLUDE -> state.currentConfig.copy(exclude = ExcludeSelectorCodec.merge(state.currentConfig.exclude, selector))
            }
            state.copy(currentConfig = updated)
        }
    }

    fun setActiveHistoryTab(tab: Int) { _uiState.update { it.copy(activeHistoryTab = tab) } }
    fun applyPresetState(name: String, config: ScraperConfig) { _uiState.update { it.copy(currentPresetName = name, currentConfig = config) } }
    fun updateCurrentConfig(updater: (ScraperConfig) -> ScraperConfig) { _uiState.update { it.copy(currentConfig = updater(it.currentConfig)) } }

    // ---- 翻訳関連のメソッド ----

    fun setActiveTranslationEngine(engine: TranslationEngine) {
        _uiState.update { it.copy(activeTranslationEngine = engine) }
    }

    /** OSピッカーで選択されたフォルダをキューに追加（重複防止） */
    fun addTranslationFolder(engine: TranslationEngine, uri: Uri, folderName: String) {
        translationManager.addFolder(engine, uri, folderName)
    }

    /** 選択中フォルダをキューから1件削除 */
    fun removeTranslationFolder(engine: TranslationEngine, index: Int) {
        translationManager.removeFolder(engine, index)
    }

    /** 選択中フォルダのキューを全解除 */
    fun clearTranslationFolders(engine: TranslationEngine) {
        translationManager.clearFolders(engine)
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

    /**
     * スクレイピングタスク・Google翻訳・DeepL翻訳の状態を一元管理し、
     * ServiceController を通じて的確にService通知を更新する。
     */
    private fun syncServiceStatus() {
        val scrapingCount = taskList.size
        // Manager のフローを直接読む（collect経由のuiState合成には伝播遅延があるため）
        val googleState = translationManager.googleState.value
        val deeplState = translationManager.deeplState.value
        val isTranslating = googleState.isTranslating || deeplState.isTranslating

        if (scrapingCount > 0 || isTranslating) {
            val statusParts = mutableListOf<String>()
            if (scrapingCount > 0) {
                statusParts.add("スクレイプト: 件")
            }
            if (googleState.isTranslating) {
                val gPrefix = if (googleState.selectedFolders.size > 1) "[/] " else ""
                statusParts.add("Google: /件")
            }
            if (deeplState.isTranslating) {
                val dPrefix = if (deeplState.selectedFolders.size > 1) "[/] " else ""
                statusParts.add("DeepL: /件")
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
                    removeTask(task)
                }
                override fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String) {
                    viewModelScope.launch(Dispatchers.IO) {
                        fileRepository.saveChapter(folderName, title, content, chapterNum)
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
            val result = repository.exportPresets(uri)
            val msg = if (result.isSuccess) {
                "プリセットをエクスポートしました"
            } else {
                "エクスポートに失敗しました: "
            }
            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
        }
    }

    fun importPresets(uri: Uri) {
        viewModelScope.launch {
            val result = repository.importPresets(uri)
            val msg = if (result.isSuccess) {
                " 件のプリセットをインポートしました"
            } else {
                val ex = result.exceptionOrNull()
                val errorDetails = when (ex) {
                    is kotlinx.serialization.SerializationException -> "無効なファイル形式です"
                    is IllegalArgumentException -> ex.message ?: "無効なデータです"
                    else -> ex?.message ?: "不明なエラー"
                }
                "インポートに失敗しました: "
            }
            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        taskList.forEach { it.stop() }
        taskList.clear()
        translationManager.shutdown()
    }
}