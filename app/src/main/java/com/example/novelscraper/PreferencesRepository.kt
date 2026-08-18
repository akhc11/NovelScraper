package com.example.novelscraper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        val DARK_MODE = stringPreferencesKey("is_dark_mode")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val presetsFlow: Flow<Map<String, ScraperConfig>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[PreferencesKeys.PRESETS] ?: "{}"
        try { json.decodeFromString<Map<String, ScraperConfig>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }.flowOn(Dispatchers.IO)

    val favoritesFlow: Flow<Map<String, String>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[PreferencesKeys.FAVORITES] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }.flowOn(Dispatchers.IO)

    val historyFlow: Flow<Map<String, HistoryItem>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[PreferencesKeys.HISTORY] ?: "{}"
        try { json.decodeFromString<Map<String, HistoryItem>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }.flowOn(Dispatchers.IO)

    val darkModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DARK_MODE]?.toBoolean() ?: false
    }.flowOn(Dispatchers.IO)

    suspend fun saveDarkMode(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.dataStore.edit { it[PreferencesKeys.DARK_MODE] = enabled.toString() }
    }

    suspend fun savePresets(presets: Map<String, ScraperConfig>) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences -> preferences[PreferencesKeys.PRESETS] = json.encodeToString(presets) }
    }

    suspend fun updatePresets(transform: (MutableMap<String, ScraperConfig>) -> Unit) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[PreferencesKeys.PRESETS]
            val current = if (rawJson.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                try {
                    json.decodeFromString<Map<String, ScraperConfig>>(rawJson).toMutableMap()
                } catch (e: Exception) {
                    Log.e("PreferencesRepository", "Failed to decode presets JSON, aborting write", e)
                    return@edit
                }
            }
            transform(current)
            preferences[PreferencesKeys.PRESETS] = json.encodeToString(current)
        }
    }

    suspend fun updateFavorites(transform: (MutableMap<String, String>) -> Unit) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[PreferencesKeys.FAVORITES]
            val current = if (rawJson.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                try {
                    json.decodeFromString<Map<String, String>>(rawJson).toMutableMap()
                } catch (e: Exception) {
                    Log.e("PreferencesRepository", "Failed to decode favorites JSON, aborting write", e)
                    return@edit
                }
            }
            transform(current)
            preferences[PreferencesKeys.FAVORITES] = json.encodeToString(current)
        }
    }

    suspend fun updateHistory(transform: (MutableMap<String, HistoryItem>) -> Unit) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[PreferencesKeys.HISTORY]
            val current = if (rawJson.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                try {
                    json.decodeFromString<Map<String, HistoryItem>>(rawJson).toMutableMap()
                } catch (e: Exception) {
                    Log.e("PreferencesRepository", "Failed to decode history JSON, aborting write", e)
                    return@edit
                }
            }
            transform(current)
            pruneHistoryMap(current)
            preferences[PreferencesKeys.HISTORY] = json.encodeToString(current)
        }
    }

    private fun pruneHistoryMap(map: MutableMap<String, HistoryItem>) {
        if (map.size > MAX_HISTORY_SIZE) {
            val excessCount = map.size - MAX_HISTORY_SIZE
            val keysToRemove = map.entries
                .sortedBy { it.value.timestamp }
                .take(excessCount)
                .map { it.key }
            keysToRemove.forEach { map.remove(it) }
        }
    }

    val setupDoneFlow: Flow<Boolean> = context.dataStore.data.map { it[PreferencesKeys.SETUP_DONE]?.toBoolean() ?: false }.flowOn(Dispatchers.IO)
    suspend fun saveSetupDone(done: Boolean) = withContext(Dispatchers.IO) { context.dataStore.edit { it[PreferencesKeys.SETUP_DONE] = done.toString() } }

    suspend fun exportPresets(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val presetsJson = context.dataStore.data.map { it[PreferencesKeys.PRESETS] ?: "{}" }.first()
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(presetsJson.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("Failed to open output stream")
        }
    }

    suspend fun importPresets(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonStr = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: throw java.io.IOException("Failed to open input stream")

            val imported = json.decodeFromString<Map<String, ScraperConfig>>(jsonStr)
            if (imported.isEmpty()) {
                throw java.lang.IllegalArgumentException("Imported file has no presets")
            }
            if (imported.any { it.key.trim().isEmpty() }) {
                throw java.lang.IllegalArgumentException("Preset names cannot be empty")
            }

            updatePresets { current ->
                current.putAll(imported)
            }
            imported.size
        }
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }
}