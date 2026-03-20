package com.example.novelscraper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    
    private val taskList = CopyOnWriteArrayList<ScrapingTask>()
    private val _activeTasks = MutableStateFlow<List<ScrapingTask>>(emptyList())
    val activeTasks: StateFlow<List<ScrapingTask>> = _activeTasks.asStateFlow()

    private val _presets = MutableStateFlow<Map<String, ScraperConfig>>(emptyMap())
    val presets: StateFlow<Map<String, ScraperConfig>> = _presets.asStateFlow()

    private val _favorites = MutableStateFlow<Map<String, String>>(emptyMap())
    val favorites: StateFlow<Map<String, String>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<Map<String, HistoryItem>>(emptyMap())
    val history: StateFlow<Map<String, HistoryItem>> = _history.asStateFlow()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.presetsFlow.collect { _presets.value = it }
        }
        viewModelScope.launch {
            repository.favoritesFlow.collect { _favorites.value = it }
        }
        viewModelScope.launch {
            repository.historyFlow.collect { _history.value = it }
        }
        
        // 初期設定の流し込み
        viewModelScope.launch {
            repository.setupDoneFlow.collect { done ->
                if (!done) {
                    val initialPresets = mutableMapOf<String, ScraperConfig>()
                    
                    initialPresets["自動検出(推奨)"] = ScraperConfig(
                        regex = "\\s*[-|｜].*|\\(.*\\)|\\[.*\\]",
                        endCheck = "list|index|toc|javascript|null"
                    )
                    
                    initialPresets["ハーメルン"] = ScraperConfig(
                        body = "#honbun",
                        title = "div > span[style*='font-size:120%']",
                        regex = "\\s*[-|｜].*",
                        delay = "5-10",
                        endCheck = "list|index|toc|javascript|null",
                        autoUrl = "syosetu.org"
                    )
                    
                    repository.savePresets(initialPresets)
                    repository.saveSetupDone(true)
                }
            }
        }
    }

    fun addTask(task: ScrapingTask) {
        taskList.add(task)
        _activeTasks.value = taskList.toList()
    }

    fun removeTask(task: ScrapingTask) {
        taskList.remove(task)
        _activeTasks.value = taskList.toList()
    }

    fun updateStatus() {
        _activeTasks.value = taskList.toList()
    }

    fun savePreset(name: String, config: ScraperConfig) {
        viewModelScope.launch {
            repository.updatePresets { it[name] = config }
        }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            repository.updatePresets { it.remove(name) }
        }
    }

    fun saveFavorite(name: String, url: String) {
        viewModelScope.launch {
            repository.updateFavorites { it[name] = url }
        }
    }

    fun deleteFavorite(name: String) {
        viewModelScope.launch {
            repository.updateFavorites { it.remove(name) }
        }
    }

    fun updateHistory(folder: String, title: String, chap: String, url: String, config: ScraperConfig) {
        viewModelScope.launch {
            val item = HistoryItem(
                title = title,
                chapter = chap,
                url = url,
                config = config,
                time = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
            )
            repository.updateHistory { it[folder] = item }
        }
    }

    fun deleteHistory(folder: String) {
        viewModelScope.launch {
            repository.updateHistory { it.remove(folder) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.updateHistory { it.clear() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        taskList.forEach { it.stop() }
        taskList.clear()
        _activeTasks.value = emptyList()
    }

    // --- UI State Management ---

    fun setOpenedPanel(panel: PanelType) {
        _uiState.update { 
            it.copy(openedPanel = if (it.openedPanel == panel) PanelType.NONE else panel) 
        }
    }

    fun closePanels() {
        _uiState.update { it.copy(openedPanel = PanelType.NONE) }
    }

    fun toggleInspectMode() {
        _uiState.update { it.copy(isInspectMode = !it.isInspectMode) }
    }

    fun toggleBlockImages() {
        _uiState.update { it.copy(blockImages = !it.blockImages) }
    }

    fun setInputUrl(url: String) {
        _uiState.update { it.copy(inputUrl = url) }
    }

    fun setCurrentUrl(url: String) {
        _uiState.update { it.copy(currentUrl = url) }
    }

    fun setActiveHistoryTab(tab: Int) {
        _uiState.update { it.copy(activeHistoryTab = tab) }
    }

    fun applyPresetState(name: String, config: ScraperConfig) {
        _uiState.update { it.copy(currentPresetName = name, currentConfig = config) }
    }

    fun updateCurrentConfig(updater: (ScraperConfig) -> ScraperConfig) {
        _uiState.update { it.copy(currentConfig = updater(it.currentConfig)) }
    }
}
