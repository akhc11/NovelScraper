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
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
        val WEBVIEW_DARK_MODE = stringPreferencesKey("webview_dark_mode")
        val GOOGLE_CHUNK_DELAY = stringPreferencesKey("google_chunk_delay")
        val GOOGLE_FILE_DELAY = stringPreferencesKey("google_file_delay")
        val DEEPL_CHUNK_DELAY = stringPreferencesKey("deepl_chunk_delay")
        val DEEPL_FILE_DELAY = stringPreferencesKey("deepl_file_delay")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val webViewDarkModeFlow: Flow<Boolean> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.WEBVIEW_DARK_MODE] }
        .map { preferences ->
            preferences[PreferencesKeys.WEBVIEW_DARK_MODE]?.toBoolean() ?: true
        }.flowOn(Dispatchers.IO)

    suspend fun saveWebViewDarkMode(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WEBVIEW_DARK_MODE] = enabled.toString()
        }
    }

    // Google翻訳 待機時間 (例: "1-3", "30-80", "2.0")
    val googleChunkDelayFlow: Flow<String> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.GOOGLE_CHUNK_DELAY] }
        .map { preferences ->
            preferences[PreferencesKeys.GOOGLE_CHUNK_DELAY]?.ifEmpty { null } ?: "1-3"
        }.flowOn(Dispatchers.IO)

    val googleFileDelayFlow: Flow<String> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.GOOGLE_FILE_DELAY] }
        .map { preferences ->
            preferences[PreferencesKeys.GOOGLE_FILE_DELAY]?.ifEmpty { null } ?: "1-2"
        }.flowOn(Dispatchers.IO)

    suspend fun saveGoogleDelays(chunkDelay: String, fileDelay: String) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GOOGLE_CHUNK_DELAY] = chunkDelay.trim()
            preferences[PreferencesKeys.GOOGLE_FILE_DELAY] = fileDelay.trim()
        }
    }

    // DeepL翻訳 待機時間 (例: "3-8", "30-80", "2.5")
    val deeplChunkDelayFlow: Flow<String> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.DEEPL_CHUNK_DELAY] }
        .map { preferences ->
            preferences[PreferencesKeys.DEEPL_CHUNK_DELAY]?.ifEmpty { null } ?: "3-8"
        }.flowOn(Dispatchers.IO)

    val deeplFileDelayFlow: Flow<String> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.DEEPL_FILE_DELAY] }
        .map { preferences ->
            preferences[PreferencesKeys.DEEPL_FILE_DELAY]?.ifEmpty { null } ?: "2-5"
        }.flowOn(Dispatchers.IO)

    suspend fun saveDeeplDelays(chunkDelay: String, fileDelay: String) = withContext(Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEEPL_CHUNK_DELAY] = chunkDelay.trim()
            preferences[PreferencesKeys.DEEPL_FILE_DELAY] = fileDelay.trim()
        }
    }

    val presetsFlow: Flow<Map<String, ScraperConfig>> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.PRESETS] }
        .map { preferences ->
            val jsonStr = preferences[PreferencesKeys.PRESETS] ?: "{}"
            try { json.decodeFromString<Map<String, ScraperConfig>>(jsonStr) } catch (e: Exception) { emptyMap() }
        }.flowOn(Dispatchers.IO)

    val favoritesFlow: Flow<Map<String, String>> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.FAVORITES] }
        .map { preferences ->
            val jsonStr = preferences[PreferencesKeys.FAVORITES] ?: "{}"
            try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
        }.flowOn(Dispatchers.IO)

    val historyFlow: Flow<Map<String, HistoryItem>> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.HISTORY] }
        .map { preferences ->
            val jsonStr = preferences[PreferencesKeys.HISTORY] ?: "{}"
            try { json.decodeFromString<Map<String, HistoryItem>>(jsonStr) } catch (e: Exception) { emptyMap() }
        }.flowOn(Dispatchers.IO)

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

    val setupDoneFlow: Flow<Boolean> = context.dataStore.data
        .distinctUntilChangedBy { it[PreferencesKeys.SETUP_DONE] }
        .map { it[PreferencesKeys.SETUP_DONE]?.toBoolean() ?: false }
        .flowOn(Dispatchers.IO)

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