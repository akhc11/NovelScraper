package com.example.novelscraper

import android.app.Application
import com.example.novelscraper.scraper.ScraperConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 履歴・お気に入りの状態保持＋永続化を担当するストア。
 * ScrapingViewModelが所有し、同一ライフサイクル（viewModelScope）で駆動する。
 * 画面からは ScrapingViewModel 経由で集約公開し、収集点を増やさない。
 */
class LibraryStore(
    application: Application,
    private val scope: CoroutineScope
) {
    private val favoriteRepository = FavoriteRepository(application)
    private val historyRepository = HistoryRepository(application)

    private val _favorites = MutableStateFlow<Map<String, String>>(emptyMap())
    val favorites: StateFlow<Map<String, String>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<Map<String, HistoryItem>>(emptyMap())
    val history: StateFlow<Map<String, HistoryItem>> = _history.asStateFlow()

    init {
        scope.launch {
            favoriteRepository.favoritesFlow.collect { _favorites.value = it }
        }
        scope.launch {
            historyRepository.historyFlow.collect { _history.value = it }
        }
    }

    fun saveFavorite(title: String, url: String) {
        scope.launch { favoriteRepository.updateFavorites { it[title] = url } }
    }

    fun deleteFavorite(title: String) {
        scope.launch { favoriteRepository.updateFavorites { it.remove(title) } }
    }

    fun updateHistory(
        folderName: String,
        title: String,
        chapter: String,
        url: String,
        nextUrl: String,
        config: ScraperConfig,
        presetName: String
    ) {
        if (url.startsWith("javascript:") || url.startsWith("data:") || url.length > 2000) return
        val safeFolderName = folderName.ifEmpty { "(不明な作品)" }
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        val item = HistoryItem(
            title = title,
            chapter = chapter,
            url = url,
            config = config,
            time = dateStr,
            presetName = presetName,
            timestamp = System.currentTimeMillis(),
            nextUrl = nextUrl
        )
        scope.launch {
            historyRepository.updateHistory { historyMap ->
                historyMap[safeFolderName] = item
            }
        }
    }

    fun deleteHistory(folderName: String) {
        scope.launch { historyRepository.updateHistory { it.remove(folderName) } }
    }
}
