package com.example.novelscraper

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class ScrapingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)
    private val fileRepository = FileRepository(application)

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

    // Google翻訳 & DeepL翻訳の独立したバックグラウンドタスク
    private var googleTranslationTask: TranslationTask? = null
    private var deeplTranslationTask: DeeplTranslationTask? = null

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
        _currentStatusText.value = taskList.lastOrNull()?.status ?: if (taskList.isNotEmpty()) "実行中: ${taskList.size}件" else "待機中"
        syncServiceStatus()
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
        _uiState.update { it.copy(currentUrl = url, inputUrl = url) }
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

    fun togglePanel(panel: PanelType) {
        _uiState.update { it.copy(openedPanel = if (it.openedPanel == panel) PanelType.NONE else panel) }
    }

    fun closePanels() {
        _uiState.update { it.copy(openedPanel = PanelType.NONE, isInspectMode = false) }
    }

    fun setInspectMode(active: Boolean) {
        _uiState.update { it.copy(isInspectMode = active, openedPanel = if (active) PanelType.SETTINGS else it.openedPanel) }
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
        val newItem = FolderItem(
            path = uri.path ?: uri.toString(),
            name = folderName,
            uri = uri
        )
        _uiState.update { state ->
            val currState = when (engine) {
                TranslationEngine.GOOGLE -> state.googleTranslationState
                TranslationEngine.DEEPL -> state.deeplTranslationState
            }
            val existing = currState.selectedFolders
            val updatedList = if (existing.any { it.uri == uri || (it.path.isNotEmpty() && it.path == newItem.path) }) {
                existing
            } else {
                existing + newItem
            }
            val firstUri = updatedList.firstOrNull()?.uri
            val displayName = when (updatedList.size) {
                0 -> ""
                1 -> updatedList.first().name
                else -> "${updatedList.first().name} (他${updatedList.size - 1}件)"
            }

            when (engine) {
                TranslationEngine.GOOGLE -> state.copy(
                    googleTranslationState = state.googleTranslationState.copy(
                        selectedFolders = updatedList,
                        folderUri = firstUri,
                        folderName = displayName
                    )
                )
                TranslationEngine.DEEPL -> state.copy(
                    deeplTranslationState = state.deeplTranslationState.copy(
                        selectedFolders = updatedList,
                        folderUri = firstUri,
                        folderName = displayName
                    )
                )
            }
        }
    }

    /** 選択中フォルダをキューから1件削除 */
    fun removeTranslationFolder(engine: TranslationEngine, index: Int) {
        _uiState.update { state ->
            val currState = when (engine) {
                TranslationEngine.GOOGLE -> state.googleTranslationState
                TranslationEngine.DEEPL -> state.deeplTranslationState
            }
            val updatedList = currState.selectedFolders.toMutableList().apply {
                if (index in indices) removeAt(index)
            }
            val firstUri = updatedList.firstOrNull()?.uri
            val displayName = when (updatedList.size) {
                0 -> ""
                1 -> updatedList.first().name
                else -> "${updatedList.first().name} (他${updatedList.size - 1}件)"
            }

            when (engine) {
                TranslationEngine.GOOGLE -> state.copy(
                    googleTranslationState = state.googleTranslationState.copy(
                        selectedFolders = updatedList,
                        folderUri = firstUri,
                        folderName = displayName
                    )
                )
                TranslationEngine.DEEPL -> state.copy(
                    deeplTranslationState = state.deeplTranslationState.copy(
                        selectedFolders = updatedList,
                        folderUri = firstUri,
                        folderName = displayName
                    )
                )
            }
        }
    }

    /** 選択中フォルダのキューを全解除 */
    fun clearTranslationFolders(engine: TranslationEngine) {
        _uiState.update { state ->
            when (engine) {
                TranslationEngine.GOOGLE -> state.copy(
                    googleTranslationState = state.googleTranslationState.copy(
                        selectedFolders = emptyList(),
                        folderUri = null,
                        folderName = ""
                    )
                )
                TranslationEngine.DEEPL -> state.copy(
                    deeplTranslationState = state.deeplTranslationState.copy(
                        selectedFolders = emptyList(),
                        folderUri = null,
                        folderName = ""
                    )
                )
            }
        }
    }

    /** 単一フォルダ（SAF経由等）をセット */
    fun setTranslationFolder(engine: TranslationEngine, uri: Uri, folderName: String) {
        addTranslationFolder(engine, uri, folderName)
    }

    fun setTranslationLanguages(engine: TranslationEngine, source: String, target: String) {
        _uiState.update { state ->
            when (engine) {
                TranslationEngine.GOOGLE -> state.copy(
                    googleTranslationState = state.googleTranslationState.copy(
                        sourceLang = source,
                        targetLang = target
                    )
                )
                TranslationEngine.DEEPL -> state.copy(
                    deeplTranslationState = state.deeplTranslationState.copy(
                        sourceLang = source,
                        targetLang = target
                    )
                )
            }
        }
    }

    fun startTranslation(engine: TranslationEngine) {
        val engineState = when (engine) {
            TranslationEngine.GOOGLE -> _uiState.value.googleTranslationState
            TranslationEngine.DEEPL -> _uiState.value.deeplTranslationState
        }

        val folders = engineState.selectedFolders
        if (folders.isEmpty() && engineState.folderUri == null) {
            Toast.makeText(getApplication(), "翻訳対象のフォルダを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (engineState.isTranslating) return

        // 複数フォルダキューの先頭から開始
        startNextFolderInQueue(engine, 0)
    }

    /**
     * 複数フォルダ連続翻訳キューの実行制御。
     * 現在のフォルダの全話が完了したら自動的に次のフォルダへ進む。
     */
    private fun startNextFolderInQueue(engine: TranslationEngine, folderIndex: Int) {
        val state = _uiState.value
        val engineState = when (engine) {
            TranslationEngine.GOOGLE -> state.googleTranslationState
            TranslationEngine.DEEPL -> state.deeplTranslationState
        }

        val folders = engineState.selectedFolders.ifEmpty {
            if (engineState.folderUri != null) {
                listOf(FolderItem(path = engineState.folderUri.path ?: "", name = engineState.folderName, uri = engineState.folderUri))
            } else emptyList()
        }

        if (folders.isEmpty() || folderIndex >= folders.size) {
            // 全フォルダの連続翻訳が完了
            _uiState.update { s ->
                when (engine) {
                    TranslationEngine.GOOGLE -> s.copy(
                        googleTranslationState = s.googleTranslationState.copy(
                            isTranslating = false,
                            statusText = "全 ${folders.size} フォルダの翻訳が完了しました"
                        )
                    )
                    TranslationEngine.DEEPL -> s.copy(
                        deeplTranslationState = s.deeplTranslationState.copy(
                            isTranslating = false,
                            statusText = "全 ${folders.size} フォルダの翻訳が完了しました"
                        )
                    )
                }
            }
            val engineName = if (engine == TranslationEngine.GOOGLE) "Google" else "DeepL"
            Toast.makeText(getApplication(), "[$engineName 翻訳] 全 ${folders.size} フォルダの翻訳が完了しました", Toast.LENGTH_LONG).show()
            syncServiceStatus()
            return
        }

        val currentItem = folders[folderIndex]
        val targetUri = currentItem.uri ?: Uri.fromFile(File(currentItem.path))
        val folderProgressPrefix = if (folders.size > 1) "[フォルダ ${folderIndex + 1}/${folders.size}] " else ""

        _uiState.update { s ->
            when (engine) {
                TranslationEngine.GOOGLE -> s.copy(
                    googleTranslationState = s.googleTranslationState.copy(
                        currentFolderIndex = folderIndex,
                        folderUri = targetUri,
                        folderName = if (folders.size == 1) currentItem.name else "${currentItem.name} (${folderIndex + 1}/${folders.size})",
                        isTranslating = true,
                        statusText = "${folderProgressPrefix}${currentItem.name} を開始中...",
                        progress = Pair(0, 0),
                        chunkProgress = Pair(0, 0)
                    )
                )
                TranslationEngine.DEEPL -> s.copy(
                    deeplTranslationState = s.deeplTranslationState.copy(
                        currentFolderIndex = folderIndex,
                        folderUri = targetUri,
                        folderName = if (folders.size == 1) currentItem.name else "${currentItem.name} (${folderIndex + 1}/${folders.size})",
                        isTranslating = true,
                        statusText = "${folderProgressPrefix}${currentItem.name} を開始中...",
                        progress = Pair(0, 0),
                        chunkProgress = Pair(0, 0)
                    )
                )
            }
        }
        syncServiceStatus()

        when (engine) {
            TranslationEngine.GOOGLE -> {
                val task = TranslationTask(
                    context = getApplication(),
                    folderUri = targetUri,
                    sourceLang = engineState.sourceLang,
                    targetLang = engineState.targetLang,
                    listener = object : TranslationTask.TranslationListener {
                        override fun onProgress(
                            completedFiles: Int,
                            totalFiles: Int,
                            currentFileName: String,
                            currentChunk: Int,
                            totalChunks: Int,
                            statusText: String
                        ) {
                            _uiState.update { s ->
                                s.copy(
                                    googleTranslationState = s.googleTranslationState.copy(
                                        progress = Pair(completedFiles, totalFiles),
                                        currentFileName = currentFileName,
                                        chunkProgress = Pair(currentChunk, totalChunks),
                                        statusText = "${folderProgressPrefix}$statusText"
                                    )
                                )
                            }
                            syncServiceStatus()
                        }

                        override fun onFileTranslated(fileName: String, success: Boolean) {}

                        override fun onTaskFinished(success: Boolean, message: String) {
                            googleTranslationTask = null
                            if (success && _uiState.value.googleTranslationState.isTranslating && folderIndex + 1 < folders.size) {
                                // 次のフォルダへ自動遷移
                                startNextFolderInQueue(engine, folderIndex + 1)
                            } else {
                                _uiState.update { s ->
                                    s.copy(
                                        googleTranslationState = s.googleTranslationState.copy(
                                            isTranslating = false,
                                            statusText = "${folderProgressPrefix}$message"
                                        )
                                    )
                                }
                                Toast.makeText(getApplication(), "[Google翻訳] $message", Toast.LENGTH_LONG).show()
                                syncServiceStatus()
                            }
                        }
                    }
                )
                googleTranslationTask = task
                task.start()
            }
            TranslationEngine.DEEPL -> {
                val task = DeeplTranslationTask(
                    context = getApplication(),
                    folderUri = targetUri,
                    sourceLang = engineState.sourceLang,
                    targetLang = engineState.targetLang,
                    listener = object : TranslationTask.TranslationListener {
                        override fun onProgress(
                            completedFiles: Int,
                            totalFiles: Int,
                            currentFileName: String,
                            currentChunk: Int,
                            totalChunks: Int,
                            statusText: String
                        ) {
                            _uiState.update { s ->
                                s.copy(
                                    deeplTranslationState = s.deeplTranslationState.copy(
                                        progress = Pair(completedFiles, totalFiles),
                                        currentFileName = currentFileName,
                                        chunkProgress = Pair(currentChunk, totalChunks),
                                        statusText = "${folderProgressPrefix}$statusText"
                                    )
                                )
                            }
                            syncServiceStatus()
                        }

                        override fun onFileTranslated(fileName: String, success: Boolean) {}

                        override fun onTaskFinished(success: Boolean, message: String) {
                            deeplTranslationTask = null
                            if (success && _uiState.value.deeplTranslationState.isTranslating && folderIndex + 1 < folders.size) {
                                // 次のフォルダへ自動遷移
                                startNextFolderInQueue(engine, folderIndex + 1)
                            } else {
                                _uiState.update { s ->
                                    s.copy(
                                        deeplTranslationState = s.deeplTranslationState.copy(
                                            isTranslating = false,
                                            statusText = "${folderProgressPrefix}$message"
                                        )
                                    )
                                }
                                Toast.makeText(getApplication(), "[DeepL翻訳] $message", Toast.LENGTH_LONG).show()
                                syncServiceStatus()
                            }
                        }
                    }
                )
                deeplTranslationTask = task
                task.start()
            }
        }
    }

    fun stopTranslation(engine: TranslationEngine) {
        when (engine) {
            TranslationEngine.GOOGLE -> {
                googleTranslationTask?.stop()
                googleTranslationTask = null
                _uiState.update {
                    it.copy(
                        googleTranslationState = it.googleTranslationState.copy(
                            isTranslating = false,
                            statusText = "Google翻訳を停止しました"
                        )
                    )
                }
            }
            TranslationEngine.DEEPL -> {
                deeplTranslationTask?.stop()
                deeplTranslationTask = null
                _uiState.update {
                    it.copy(
                        deeplTranslationState = it.deeplTranslationState.copy(
                            isTranslating = false,
                            statusText = "DeepL翻訳を停止しました"
                        )
                    )
                }
            }
        }
        syncServiceStatus()
    }

    /**
     * スクレイピングタスク・Google翻訳・DeepL翻訳の状態を一元管理し、
     * タスク変更時のみ的確にService通知を更新する。
     */
    private fun syncServiceStatus() {
        val scrapingCount = taskList.size
        val googleState = _uiState.value.googleTranslationState
        val deeplState = _uiState.value.deeplTranslationState
        val isTranslating = googleState.isTranslating || deeplState.isTranslating

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
            val msg = if (statusParts.isNotEmpty()) {
                statusParts.joinToString(" / ")
            } else {
                "処理中..."
            }
            updateServiceNotification(msg)
        } else {
            try {
                val stopIntent = Intent(getApplication(), ScraperService::class.java)
                getApplication<Application>().stopService(stopIntent)
            } catch (_: Exception) {}
        }
    }

    private fun updateServiceNotification(msg: String) {
        try {
            val intent = Intent(getApplication(), ScraperService::class.java).apply {
                action = ScraperService.ACTION_UPDATE_STATUS
                putExtra(ScraperService.EXTRA_MSG, msg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } catch (_: Exception) {
            // Service更新時の例外防止
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
                "エクスポートに失敗しました: ${result.exceptionOrNull()?.message}"
            }
            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
        }
    }

    fun importPresets(uri: Uri) {
        viewModelScope.launch {
            val result = repository.importPresets(uri)
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
            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        taskList.forEach { it.stop() }
        taskList.clear()
        googleTranslationTask?.stop()
        googleTranslationTask = null
        deeplTranslationTask?.stop()
        deeplTranslationTask = null
    }
}
