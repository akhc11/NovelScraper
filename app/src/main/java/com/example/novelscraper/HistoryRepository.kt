package com.example.novelscraper

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** 履歴永続化の単一責務リポジトリ。キー・ファイル・上限100は従来通りで移行不要。 */
class HistoryRepository(private val context: Context) {

    val historyFlow: Flow<Map<String, HistoryItem>> = context.appDataStore.data
        .catchIo()
        .distinctUntilChangedBy { it[PrefKeys.HISTORY] }
        .map { preferences ->
            decodeOrEmptyMap<HistoryItem>(preferences[PrefKeys.HISTORY] ?: "{}")
        }.io()

    suspend fun updateHistory(transform: (MutableMap<String, HistoryItem>) -> Unit) =
        withContext(Dispatchers.IO) {
            context.appDataStore.edit { preferences ->
                val jsonStr = preferences[PrefKeys.HISTORY] ?: "{}"
                // 破損JSON時は上書き中断し既存データを保護する（全消去の防止）。
                val current = decodeOrAbort<Map<String, HistoryItem>>(jsonStr, "history")
                    ?.toMutableMap() ?: return@edit
                transform(current)
                pruneHistoryMap(current)
                preferences[PrefKeys.HISTORY] = dataStoreJson.encodeToString(current)
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

    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }
}
