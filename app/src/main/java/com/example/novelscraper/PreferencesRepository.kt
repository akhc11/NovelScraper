package com.example.novelscraper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "novel_scraper_prefs")

@Serializable
data class HistoryItem(
    val title: String,
    val chapter: String,
    val url: String,
    val config: ScraperConfig,
    val time: String,
    val presetName: String = "",
    val timestamp: Long = System.currentTimeMillis(), // ソート用（年またぎ対応）
    val nextUrl: String = "" // 再開時、次のページから開始するため
)

class PreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val PRESETS = stringPreferencesKey("presets_json_v2")
        val FAVORITES = stringPreferencesKey("favorites_json_v2")
        val HISTORY = stringPreferencesKey("history_json_v2")
        val SETUP_DONE = stringPreferencesKey("is_setup_done")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val presetsFlow: Flow<Map<String, ScraperConfig>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[PreferencesKeys.PRESETS] ?: "{}"
        try { json.decodeFromString<Map<String, ScraperConfig>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val favoritesFlow: Flow<Map<String, String>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[PreferencesKeys.FAVORITES] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val historyFlow: Flow<Map<String, HistoryItem>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[PreferencesKeys.HISTORY] ?: "{}"
        try { json.decodeFromString<Map<String, HistoryItem>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    suspend fun savePresets(presets: Map<String, ScraperConfig>) {
        context.dataStore.edit { preferences -> preferences[PreferencesKeys.PRESETS] = json.encodeToString(presets) }
    }

    suspend fun updatePresets(transform: (MutableMap<String, ScraperConfig>) -> Unit) {
        context.dataStore.edit { preferences ->
            val current = try { json.decodeFromString<Map<String, ScraperConfig>>(preferences[PreferencesKeys.PRESETS] ?: "{}").toMutableMap() } catch (e: Exception) { mutableMapOf() }
            transform(current)
            preferences[PreferencesKeys.PRESETS] = json.encodeToString(current)
        }
    }

    suspend fun updateFavorites(transform: (MutableMap<String, String>) -> Unit) {
        context.dataStore.edit { preferences ->
            val current = try { json.decodeFromString<Map<String, String>>(preferences[PreferencesKeys.FAVORITES] ?: "{}").toMutableMap() } catch (e: Exception) { mutableMapOf() }
            transform(current)
            preferences[PreferencesKeys.FAVORITES] = json.encodeToString(current)
        }
    }

    suspend fun updateHistory(transform: (MutableMap<String, HistoryItem>) -> Unit) {
        context.dataStore.edit { preferences ->
            val current = try { json.decodeFromString<Map<String, HistoryItem>>(preferences[PreferencesKeys.HISTORY] ?: "{}").toMutableMap() } catch (e: Exception) { mutableMapOf() }
            transform(current)
            preferences[PreferencesKeys.HISTORY] = json.encodeToString(current)
        }
    }

    val setupDoneFlow: Flow<Boolean> = context.dataStore.data.map { it[PreferencesKeys.SETUP_DONE]?.toBoolean() ?: false }
    suspend fun saveSetupDone(done: Boolean) { context.dataStore.edit { it[PreferencesKeys.SETUP_DONE] = done.toString() } }
}