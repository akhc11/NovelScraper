package com.example.novelscraper

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.example.novelscraper.scraper.ScraperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

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

    // 図書系（プリセット・お気に入り・履歴）は特化リポジトリへ分離。
    // 設定・遅延・encoding系のみを担当する。キー定義はPrefKeysへ集約。

    val webViewDarkModeFlow: Flow<Boolean> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.WEBVIEW_DARK_MODE] }
        .map { preferences ->
            preferences[PrefKeys.WEBVIEW_DARK_MODE]?.toBoolean() ?: true
        }.flowOn(Dispatchers.IO)

    suspend fun saveWebViewDarkMode(isDark: Boolean) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.WEBVIEW_DARK_MODE] = isDark.toString()
        }
    }

    val googleChunkDelayFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.GOOGLE_CHUNK_DELAY] }
        .map { it[PrefKeys.GOOGLE_CHUNK_DELAY] ?: "1-3" }.flowOn(Dispatchers.IO)

    val googleFileDelayFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.GOOGLE_FILE_DELAY] }
        .map { it[PrefKeys.GOOGLE_FILE_DELAY] ?: "1-2" }.flowOn(Dispatchers.IO)

    val deeplChunkDelayFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.DEEPL_CHUNK_DELAY] }
        .map { it[PrefKeys.DEEPL_CHUNK_DELAY] ?: "3-8" }.flowOn(Dispatchers.IO)

    val deeplFileDelayFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.DEEPL_FILE_DELAY] }
        .map { it[PrefKeys.DEEPL_FILE_DELAY] ?: "2-5" }.flowOn(Dispatchers.IO)

    val papagoChunkDelayFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.PAPAGO_CHUNK_DELAY] }
        .map { it[PrefKeys.PAPAGO_CHUNK_DELAY] ?: "3-8" }.flowOn(Dispatchers.IO)

    val papagoFileDelayFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.PAPAGO_FILE_DELAY] }
        .map { it[PrefKeys.PAPAGO_FILE_DELAY] ?: "2-5" }.flowOn(Dispatchers.IO)

    suspend fun saveGoogleDelays(chunkDelay: String, fileDelay: String) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.GOOGLE_CHUNK_DELAY] = chunkDelay
            preferences[PrefKeys.GOOGLE_FILE_DELAY] = fileDelay
        }
    }

    suspend fun saveDeeplDelays(chunkDelay: String, fileDelay: String) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.DEEPL_CHUNK_DELAY] = chunkDelay
            preferences[PrefKeys.DEEPL_FILE_DELAY] = fileDelay
        }
    }

    suspend fun savePapagoDelays(chunkDelay: String, fileDelay: String) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.PAPAGO_CHUNK_DELAY] = chunkDelay
            preferences[PrefKeys.PAPAGO_FILE_DELAY] = fileDelay
        }
    }

    val isWebSplitEnabledFlow: Flow<Boolean> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.WEB_SPLIT_ENABLED] }
        .map { it[PrefKeys.WEB_SPLIT_ENABLED]?.toBoolean() ?: false }
        .flowOn(Dispatchers.IO)

    suspend fun saveWebSplitEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.WEB_SPLIT_ENABLED] = enabled.toString()
        }
    }

    val webSplitSizeCharsFlow: Flow<Int> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.WEB_SPLIT_SIZE_CHARS] }
        .map { it[PrefKeys.WEB_SPLIT_SIZE_CHARS]?.toIntOrNull() ?: 8000 }
        .flowOn(Dispatchers.IO)

    suspend fun saveWebSplitSizeChars(sizeChars: Int) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.WEB_SPLIT_SIZE_CHARS] = sizeChars.coerceAtLeast(500).toString()
        }
    }

    val inputEncodingFlow: Flow<String> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.INPUT_ENCODING] }
        .map { it[PrefKeys.INPUT_ENCODING] ?: "AUTO" }
        .flowOn(Dispatchers.IO)

    suspend fun saveInputEncoding(value: String) = withContext(Dispatchers.IO) {
        context.appDataStore.edit { preferences ->
            preferences[PrefKeys.INPUT_ENCODING] = value
        }
    }

    val setupDoneFlow: Flow<Boolean> = context.appDataStore.data
        .distinctUntilChangedBy { it[PrefKeys.SETUP_DONE] }
        .map { it[PrefKeys.SETUP_DONE]?.toBoolean() ?: false }
        .flowOn(Dispatchers.IO)

    suspend fun saveSetupDone(done: Boolean) = withContext(Dispatchers.IO) { context.appDataStore.edit { it[PrefKeys.SETUP_DONE] = done.toString() } }
}