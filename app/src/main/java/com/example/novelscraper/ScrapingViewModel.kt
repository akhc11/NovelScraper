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
        // 過去のバグで保存されてしまった不正な履歴（巨大なJS文字列など）を起動時にクリーンアップ
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
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.updatePresets { it.remove(name) }
            if (_uiState.value.currentPresetName == name) {
                _uiState.update { it.copy(currentPresetName = "", currentConfig = ScraperConfig()) }
            }
        }
    }

    fun saveFavorite(name: String, url: String) {
        viewModelScope.launch { repository.updateFavorites { it[name] = url } }
    }

    fun deleteFavorite(name: String) {
        viewModelScope.launch { repository.updateFavorites { it.remove(name) } }
    }

    fun updateHistory(folder: String, title: String, chap: String, url: String, nextUrl: String, config: ScraperConfig) {        
        viewModelScope.launch {
            val item = HistoryItem(
                title = title,
                chapter = chap,
                url = url,
                config = config,
                time = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                presetName = _uiState.value.currentPresetName,
                nextUrl = nextUrl
            )
            repository.updateHistory { it[folder] = item }
        }
    }

    fun deleteHistory(folder: String) {
        viewModelScope.launch { repository.updateHistory { it.remove(folder) } }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.updateHistory { it.clear() } }
    }

    private fun checkForAutoPreset(url: String) {
        val currentPresets = presets.value
        if (url.isEmpty() || currentPresets.isEmpty()) return
        for ((name, config) in currentPresets) {
            if (config.autoUrl.isNotEmpty() && url.contains(config.autoUrl)) {
                if (_uiState.value.currentPresetName != name) {
                    applyPresetState(name, config)
                }
                break
            }
        }
    }

    fun setCurrentUrl(url: String) {
        _uiState.update { it.copy(currentUrl = url) }
        checkForAutoPreset(url)
    }

    fun setInputUrl(url: String) { _uiState.update { it.copy(inputUrl = url) } }
    fun closePanels() { _uiState.update { it.copy(openedPanel = PanelType.NONE) } }
    fun setOpenedPanel(panel: PanelType) { _uiState.update { it.copy(openedPanel = if (it.openedPanel == panel) PanelType.NONE else panel) } }
    fun toggleInspectMode() { _uiState.update { it.copy(isInspectMode = !it.isInspectMode) } }
    fun toggleBlockImages() { _uiState.update { it.copy(blockImages = !it.blockImages) } }
    fun toggleDesktopMode() { _uiState.update { it.copy(isDesktopMode = !it.isDesktopMode) } }
    fun toggleWebViewDarkMode() {
        val newMode = !_uiState.value.isWebViewDarkMode
        _uiState.update { it.copy(isWebViewDarkMode = newMode) }
        viewModelScope.launch { repository.saveWebViewDarkMode(newMode) }
    }
    fun setActiveHistoryTab(tab: Int) { _uiState.update { it.copy(activeHistoryTab = tab) } }
    fun applyPresetState(name: String, config: ScraperConfig) { _uiState.update { it.copy(currentPresetName = name, currentConfig = config) } }
    fun updateCurrentConfig(updater: (ScraperConfig) -> ScraperConfig) { _uiState.update { it.copy(currentConfig = updater(it.currentConfig)) } }

    // 翻訳関連のメソッド
    fun setActiveTranslationEngine(engine: TranslationEngine) {
        _uiState.update { it.copy(activeTranslationEngine = engine) }
    }

    fun setTranslationFolder(engine: TranslationEngine, uri: Uri, folderName: String) {
        _uiState.update { state ->
            when (engine) {
                TranslationEngine.GOOGLE -> state.copy(
                    googleTranslationState = state.googleTranslationState.copy(
                        folderUri = uri,
                        folderName = folderName
                    )
                )
                TranslationEngine.DEEPL -> state.copy(
                    deeplTranslationState = state.deeplTranslationState.copy(
                        folderUri = uri,
                        folderName = folderName
                    )
                )
            }
        }
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

        val folderUri = engineState.folderUri
        if (folderUri == null) {
            Toast.makeText(getApplication(), "翻訳対象のフォルダを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (engineState.isTranslating) return

        when (engine) {
            TranslationEngine.GOOGLE -> {
                _uiState.update {
                    it.copy(
                        googleTranslationState = it.googleTranslationState.copy(
                            isTranslating = true,
                            statusText = "Google翻訳を開始中...",
                            progress = Pair(0, 0),
                            chunkProgress = Pair(0, 0)
                        )
                    )
                }
                syncServiceStatus()

                val task = TranslationTask(
                    context = getApplication(),
                    folderUri = folderUri,
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
                            _uiState.update {
                                it.copy(
                                    googleTranslationState = it.googleTranslationState.copy(
                                        progress = Pair(completedFiles, totalFiles),
                                        currentFileName = currentFileName,
                                        chunkProgress = Pair(currentChunk, totalChunks),
                                        statusText = statusText
                                    )
                                )
                            }
                            syncServiceStatus()
                        }

                        override fun onFileTranslated(fileName: String, success: Boolean) {}

                        override fun onTaskFinished(success: Boolean, message: String) {
                            _uiState.update {
                                it.copy(
                                    googleTranslationState = it.googleTranslationState.copy(
                                        isTranslating = false,
                                        statusText = message
                                    )
                                )
                            }
                            Toast.makeText(getApplication(), "[Google翻訳] $message", Toast.LENGTH_LONG).show()
                            googleTranslationTask = null
                            syncServiceStatus()
                        }
                    }
                )
                googleTranslationTask = task
                task.start()
            }
            TranslationEngine.DEEPL -> {
                _uiState.update {
                    it.copy(
                        deeplTranslationState = it.deeplTranslationState.copy(
                            isTranslating = true,
                            statusText = "DeepL翻訳を開始中...",
                            progress = Pair(0, 0),
                            chunkProgress = Pair(0, 0)
                        )
                    )
                }
                syncServiceStatus()

                val task = DeeplTranslationTask(
                    context = getApplication(),
                    folderUri = folderUri,
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
                            _uiState.update {
                                it.copy(
                                    deeplTranslationState = it.deeplTranslationState.copy(
                                        progress = Pair(completedFiles, totalFiles),
                                        currentFileName = currentFileName,
                                        chunkProgress = Pair(currentChunk, totalChunks),
                                        statusText = statusText
                                    )
                                )
                            }
                            syncServiceStatus()
                        }

                        override fun onFileTranslated(fileName: String, success: Boolean) {}

                        override fun onTaskFinished(success: Boolean, message: String) {
                            _uiState.update {
                                it.copy(
                                    deeplTranslationState = it.deeplTranslationState.copy(
                                        isTranslating = false,
                                        statusText = message
                                    )
                                )
                            }
                            Toast.makeText(getApplication(), "[DeepL翻訳] $message", Toast.LENGTH_LONG).show()
                            deeplTranslationTask = null
                            syncServiceStatus()
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
     * タスク変更時のみ的確にService通知を更新する（MainActivityからの毎フレームIPC通信を完全排除）。
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
                statusParts.add("Google: ${googleState.progress.first}/${googleState.progress.second}件")
            }
            if (deeplState.isTranslating) {
                statusParts.add("DeepL: ${deeplState.progress.first}/${deeplState.progress.second}件")
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
