package com.example.novelscraper

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** お気に入り永続化の単一責務リポジトリ。キー・ファイルは従来通りで移行不要。 */
class FavoriteRepository(private val context: Context) {

    val favoritesFlow: Flow<Map<String, String>> = context.appDataStore.data
        .catchIo()
        .distinctUntilChangedBy { it[PrefKeys.FAVORITES] }
        .map { preferences ->
            decodeOrEmptyMap<String>(preferences[PrefKeys.FAVORITES] ?: "{}")
        }.io()

    suspend fun updateFavorites(transform: (MutableMap<String, String>) -> Unit) =
        withContext(Dispatchers.IO) {
            context.appDataStore.edit { preferences ->
                val jsonStr = preferences[PrefKeys.FAVORITES] ?: "{}"
                // 破損JSON時は上書き中断し既存データを保護する（全消去の防止）。
                val current = decodeOrAbort<Map<String, String>>(jsonStr, "favorites")
                    ?.toMutableMap() ?: return@edit
                transform(current)
                preferences[PrefKeys.FAVORITES] = dataStoreJson.encodeToString(current)
            }
        }
}
