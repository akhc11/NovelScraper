package com.example.novelscraper

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.example.novelscraper.scraper.ScraperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.IOException

/** プリセット永続化の単一責務リポジトリ。キー・ファイルは従来通りで移行不要。 */
class PresetRepository(private val context: Context) {

    val presetsFlow: Flow<Map<String, ScraperConfig>> = context.appDataStore.data
        .catchIo()
        .distinctUntilChangedBy { it[PrefKeys.PRESETS] }
        .map { preferences ->
            decodeOrEmptyMap<ScraperConfig>(preferences[PrefKeys.PRESETS] ?: "{}")
        }.io()

    suspend fun updatePresets(transform: (MutableMap<String, ScraperConfig>) -> Unit) =
        withContext(Dispatchers.IO) {
            context.appDataStore.edit { preferences ->
                val jsonStr = preferences[PrefKeys.PRESETS] ?: "{}"
                val current = decodeOrAbort<Map<String, ScraperConfig>>(jsonStr, "presets")
                    ?.toMutableMap() ?: return@edit
                transform(current)
                preferences[PrefKeys.PRESETS] = dataStoreJson.encodeToString(current)
            }
        }

    suspend fun savePresets(presets: Map<String, ScraperConfig>) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.PRESETS] = dataStoreJson.encodeToString(presets)
        }
    }

    suspend fun exportPresets(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val presetsJson = context.appDataStore.data
                .map { it[PrefKeys.PRESETS] ?: "{}" }.first()
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(presetsJson.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Failed to open output stream")
        }
    }

    suspend fun importPresets(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonStr = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: throw IOException("Failed to open input stream")

            val imported = dataStoreJson.decodeFromString<Map<String, ScraperConfig>>(jsonStr)
            if (imported.isEmpty()) {
                throw IllegalArgumentException("Imported file has no presets")
            }
            if (imported.any { it.key.trim().isEmpty() }) {
                throw IllegalArgumentException("Preset names cannot be empty")
            }

            updatePresets { current ->
                current.putAll(imported)
            }
            imported.size
        }
    }
}
