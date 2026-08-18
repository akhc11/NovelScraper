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

    private var translationTask: TranslationTask? = null

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
    fun setActiveHistoryTab(tab: Int) { _uiState.update { it.copy(activeHistoryTab = tab) } }
    fun applyPresetState(name: String, config: ScraperConfig) { _uiState.update { it.copy(currentPresetName = name, currentConfig = config) } }
    fun updateCurrentConfig(updater: (ScraperConfig) -> ScraperConfig) { _uiState.update { it.copy(currentConfig = updater(it.currentConfig)) } }

    // 翻訳関連のメソッド
    fun setTranslationFolder(uri: Uri, folderName: String) {
        _uiState.update {
            it.copy(
                translationFolderUri = uri,
                translationFolderName = folderName
            )
        }
    }

    fun setTranslationLanguages(source: String, target: String) {
        _uiState.update {
            it.copy(
                translationSourceLang = source,
                translationTargetLang = target
            )
        }
    }

    fun startTranslation() {
        val folderUri = _uiState.value.translationFolderUri
        if (folderUri == null) {
            Toast.makeText(getApplication(), "翻訳対象のフォルダを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (_uiState.value.isTranslating) return

        _uiState.update {
            it.copy(
                isTranslating = true,
                translationStatusText = "翻訳タスクを開始中...",
                translationProgress = Pair(0, 0),
                translationChunkProgress = Pair(0, 0)
            )
        }

        syncServiceStatus()

        val task = TranslationTask(
            context = getApplication(),
            folderUri = folderUri,
            sourceLang = _uiState.value.translationSourceLang,
            targetLang = _uiState.value.translationTargetLang,
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
                            translationProgress = Pair(completedFiles, totalFiles),
                            translationCurrentFileName = currentFileName,
                            translationChunkProgress = Pair(currentChunk, totalChunks),
                            translationStatusText = statusText
                        )
                    }
                    syncServiceStatus()
                }

                override fun onFileTranslated(fileName: String, success: Boolean) {
                    // 個別ファイル完了
                }

                override fun onTaskFinished(success: Boolean, message: String) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            translationStatusText = message
                        )
                    }
                    Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
                    translationTask = null
                    syncServiceStatus()
                }
            }
        )
        translationTask = task
        task.start()
    }

    /**
     * スクレイピングタスク・翻訳タスクの状態を一元管理し、
     * タスク変更時のみ的確にService通知を更新する（MainActivityからの毎フレームIPC通信を完全排除）。
     */
    private fun syncServiceStatus() {
        val scrapingCount = taskList.size
        val isTranslating = _uiState.value.isTranslating
        val ui = _uiState.value

        if (scrapingCount > 0 || isTranslating) {
            val msg = when {
                scrapingCount > 0 && isTranslating ->
                    "スクレイプト: ${scrapingCount}件 / 翻訳: ${ui.translationProgress.first}/${ui.translationProgress.second}件"
                scrapingCount > 0 ->
                    taskList.lastOrNull()?.let { "${it.folderName}: ${it.status}" } ?: "実行中: ${scrapingCount}件"
                else ->
                    "翻訳中: ${ui.translationProgress.first}/${ui.translationProgress.second}件 (${ui.translationStatusText})"
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

    fun stopTranslation() {
        translationTask?.stop()
        translationTask = null
        _uiState.update {
            it.copy(
                isTranslating = false,
                translationStatusText = "翻訳を停止しました"
            )
        }
        syncServiceStatus()
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
        translationTask?.stop()
        translationTask = null
    }
}
