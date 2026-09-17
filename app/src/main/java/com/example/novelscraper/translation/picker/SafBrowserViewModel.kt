package com.example.novelscraper.translation.picker

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novelscraper.translation.v2.infra.SafFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 許可根の健全性。NO_PERMISSION=再許可が必要、UNREADABLE=移動・削除等の疑い。 */
enum class RootHealth { OK, NO_PERMISSION, UNREADABLE }

/** 純粋ヘルパー(単体テスト対象)。 */
internal fun assessRootHealth(hasPersistedPermission: Boolean, readable: Boolean): RootHealth = when {
    !hasPersistedPermission -> RootHealth.NO_PERMISSION
    !readable -> RootHealth.UNREADABLE
    else -> RootHealth.OK
}

/**
 * 自前ブラウザの薄いAndroid枠組み層。重い走査・選択保持はSafBrowserStateに寄せる。
 * 技術的根拠1行：重い処理はDispatchers.IO＋ライフサイクル連動スコープに寄せ、状態本体は枠組み非依存に保つ。
 */
class SafBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SafPermissionRepository(application.applicationContext)
    private val probeStore = SafFileStore(application.applicationContext)

    val grantedRoots: StateFlow<List<GrantedRoot>> = repository.grantedRoots
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _rootHealth = MutableStateFlow<Map<String, RootHealth>>(emptyMap())
    val rootHealth: StateFlow<Map<String, RootHealth>> = _rootHealth.asStateFlow()

    /**
     * 許可根の健全性を再検証する。メニュー表示時などに呼ぶ。
     * 技術的根拠1行：失効検知は検証責務に集約し、一覧・翻訳投入の流れは変えない（外部振る舞い不変）。
     */
    fun refreshHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>().applicationContext
                val persisted = app.contentResolver.persistedUriPermissions
                val map = grantedRoots.value.associate { root ->
                    val has = persisted.any { it.uri.toString() == root.treeUri && it.isReadPermission }
                    val readable = if (has) {
                        try {
                            probeStore.probe(root.treeUri)
                        } catch (e: Exception) {
                            Log.w("SafBrowserVM", "probe failed: ${root.treeUri}", e)
                            false
                        }
                    } else false
                    root.treeUri to assessRootHealth(has, readable)
                }
                _rootHealth.value = map
            } catch (e: Exception) {
                Log.w("SafBrowserVM", "refreshHealth failed", e)
            }
        }
    }

    private var browserState: SafBrowserState? = null

    fun openRoot(treeUri: String, displayName: String): SafBrowserState {
        closeBrowser()
        return SafBrowserState(
            store = SafFileStore(getApplication<Application>().applicationContext),
            scope = viewModelScope,
            treeUri = treeUri,
            rootName = displayName
        ).also {
            browserState = it
            it.refresh()
        }
    }

    fun closeBrowser() {
        browserState?.clearChecked()
        browserState = null
    }

    /**
     * OSピッカーで得たツリーURIの永続許可取り＋根の保存。
     * 成功時はMainスレッドで[onSaved]を呼ぶ。失敗時はLogcatに残し何もしない(無言化しない)。
     */
    fun persistRootPermission(treeUri: Uri, displayName: String, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>().applicationContext
            try {
                app.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("SafBrowserVM", "takePersistable failed: $treeUri", e)
                return@launch
            }
            try {
                repository.addRoot(treeUri.toString(), displayName)
                withContext(Dispatchers.Main) { onSaved(treeUri.toString()) }
            } catch (e: Exception) {
                Log.w("SafBrowserVM", "save root failed: $treeUri", e)
            }
        }
    }

    fun forgetRoot(treeUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.removeRoot(treeUri)
            } catch (e: Exception) {
                Log.w("SafBrowserVM", "remove root failed: $treeUri", e)
            }
        }
    }
}
