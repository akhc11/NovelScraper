package com.example.novelscraper

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * アプリ全体で単一のPreferences DataStoreインスタンスを共有するための基盤。
 * 同名で複数のdelegateを作ると多重オープンになるため、このファイルの1箇所でのみ定義する。
 */
internal val Context.appDataStore by preferencesDataStore(name = "settings")

/** DataStoreのキー定義（単一箇所管理）。キー文字列は従来通りで移行不要。 */
internal object PrefKeys {
    val PRESETS = stringPreferencesKey("presets_json_v2")
    val FAVORITES = stringPreferencesKey("favorites_json_v2")
    val HISTORY = stringPreferencesKey("history_json_v2")
    val SETUP_DONE = stringPreferencesKey("is_setup_done")
    val WEBVIEW_DARK_MODE = stringPreferencesKey("webview_dark_mode")
    val GOOGLE_CHUNK_DELAY = stringPreferencesKey("google_chunk_delay")
    val GOOGLE_FILE_DELAY = stringPreferencesKey("google_file_delay")
    val DEEPL_CHUNK_DELAY = stringPreferencesKey("deepl_chunk_delay")
    val DEEPL_FILE_DELAY = stringPreferencesKey("deepl_file_delay")
    val PAPAGO_CHUNK_DELAY = stringPreferencesKey("papago_chunk_delay")
    val PAPAGO_FILE_DELAY = stringPreferencesKey("papago_file_delay")
    val WEB_SPLIT_ENABLED = stringPreferencesKey("web_split_enabled")
    val WEB_SPLIT_SIZE_CHARS = stringPreferencesKey("web_split_size_chars")
    val INPUT_ENCODING = stringPreferencesKey("input_encoding")
    val PICKER_ROOTS = stringPreferencesKey("picker_roots_json_v1")
}

internal val dataStoreJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private const val TAG = "DataStoreJson"

/**
 * DataStore内JSONのデコード。失敗時はnullを返し、呼び元のeditは中断すること。
 * 破損データでの全消去を防ぐための共通基盤。
 */
internal inline fun <reified T> decodeOrAbort(jsonStr: String, tag: String): T? {
    return try {
        dataStoreJson.decodeFromString<T>(jsonStr)
    } catch (e: Exception) {
        Log.w(TAG, "$tag: corrupt JSON; aborting edit to protect existing data", e)
        null
    }
}

/**
 * DataStore読取Flowの公式推奨ハンドリング：読取IOException時は空で継続し、
 * 収集スコープの死滅を防ぐ。それ以外の例外は再送出する。
 */
internal fun Flow<Preferences>.catchIo(): Flow<Preferences> =
    catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

/** decode失敗時は空マップで表示継続（永続化側は中断保護されるため一時的表示のみ）。 */
internal inline fun <reified V> decodeOrEmptyMap(jsonStr: String): Map<String, V> {
    return try {
        dataStoreJson.decodeFromString<Map<String, V>>(jsonStr)
    } catch (e: Exception) {
        Log.w(TAG, "decode failed; showing empty map", e)
        emptyMap()
    }
}

internal fun <T> Flow<T>.io(): Flow<T> = flowOn(Dispatchers.IO)
