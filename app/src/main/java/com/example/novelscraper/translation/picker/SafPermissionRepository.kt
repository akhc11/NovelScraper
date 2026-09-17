package com.example.novelscraper.translation.picker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.example.novelscraper.PrefKeys
import com.example.novelscraper.appDataStore
import com.example.novelscraper.catchIo
import com.example.novelscraper.dataStoreJson
import com.example.novelscraper.decodeOrAbort
import com.example.novelscraper.io
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 許可済み親ツリーの永続レコード。選択対象(targets)はセッション保持のみで永続化しない。 */
@Serializable
data class GrantedRoot(
    val treeUri: String,
    val displayName: String,
    val grantedAt: Long = 0L
)

private const val TAG = "SafPermissionRepository"

/** 純粋ヘルパー(単体テスト対象)。表示継続用の寛容デコード。 */
internal fun decodeRootsLenient(jsonStr: String): List<GrantedRoot> {
    if (jsonStr.isBlank()) return emptyList()
    return try {
        dataStoreJson.decodeFromString(jsonStr)
    } catch (e: Exception) {
        Log.w(TAG, "decode failed; showing empty roots", e)
        emptyList()
    }
}

/** 純粋ヘルパー(単体テスト対象)。新しい許可順に並べる。 */
internal fun sortRootsByRecency(roots: List<GrantedRoot>): List<GrantedRoot> =
    roots.sortedByDescending { it.grantedAt }

/** 純粋ヘルパー(単体テスト対象)。同URI重複は最新1件に寄せる。 */
internal fun mergeGrantedRoot(
    current: List<GrantedRoot>,
    treeUri: String,
    displayName: String,
    now: Long
): List<GrantedRoot> {
    if (treeUri.isBlank()) return current
    return current.filterNot { it.treeUri == treeUri } +
        GrantedRoot(treeUri, displayName.ifBlank { FALLBACK_FOLDER_NAME }, now)
}

/**
 * 親ツリー許可の単一責務リポジトリ。targets永続は持たない(Phase 2送り)。
 * 技術的根拠1行：DataStore二重正本化を避けるため永続は許可根のみに絞り、選択列は呼出側セッションに置く。
 */
class SafPermissionRepository(private val context: Context) {

    val grantedRoots: Flow<List<GrantedRoot>> = context.appDataStore.data
        .catchIo()
        .distinctUntilChangedBy { it[PrefKeys.PICKER_ROOTS] }
        .map { preferences ->
            sortRootsByRecency(decodeRootsLenient(preferences[PrefKeys.PICKER_ROOTS] ?: "[]"))
        }.io()

    suspend fun addRoot(treeUri: String, displayName: String) =
        withContext(Dispatchers.IO) {
            context.appDataStore.edit { preferences ->
                val raw = preferences[PrefKeys.PICKER_ROOTS] ?: "[]"
                // 破損JSON時は上書き中断し既存データを保護する（全消去の防止）。
                val current = decodeOrAbort<List<GrantedRoot>>(raw, "picker-roots") ?: return@edit
                preferences[PrefKeys.PICKER_ROOTS] =
                    dataStoreJson.encodeToString(mergeGrantedRoot(current, treeUri, displayName, System.currentTimeMillis()))
            }
        }

    suspend fun removeRoot(treeUri: String) =
        withContext(Dispatchers.IO) {
            context.appDataStore.edit { preferences ->
                val raw = preferences[PrefKeys.PICKER_ROOTS] ?: return@edit
                val current = decodeOrAbort<List<GrantedRoot>>(raw, "picker-roots") ?: return@edit
                val next = current.filterNot { it.treeUri == treeUri }
                if (next.size == current.size) return@edit
                preferences[PrefKeys.PICKER_ROOTS] = dataStoreJson.encodeToString(next)
            }
        }
}
