package com.example.novelscraper.translation.v2.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.RequestOptions
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.engine.EngineState
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.engine.RunSummary
import com.example.novelscraper.translation.v2.engine.defaultHandlerFor
import com.example.novelscraper.translation.v2.infra.SafFileStore
import com.example.novelscraper.translation.v2.service.V2TranslationService
import com.example.novelscraper.translation.v2.service.V2TranslationServiceController
import com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class V2FolderItem(val uri: String, val name: String)

/**
 * v2 UI状態・イベント中継と開始前ガード（判定・巡回・保存判定の本体は下位層）。
 * 技術的根拠1行：重い実行・保存はDispatchers.IO＋viewModelScopeに寄せ、旧TranslationQueueManagerと二重管理しない。
 * フォアグラウンド通知の責務もここに集約する（開始直後に昇格→進捗追従→完了/停止で降格）。
 */
class V2TranslationViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val NOTIFICATION_TITLE = "LLM小説翻訳"
        private const val NOTIFY_THROTTLE_MS = 1500L

        // 技術的根拠1行：Activity破棄時のonClearedによる翻訳強制中断を防ぎフォアグラウンドサービスと共に完走させるためプロセス生存スコープで実行する。
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile
        private var sharedEngine: RunEngine? = null
        private var runJob: Job? = null
        private var notifyJob: Job? = null
        private var lastNotifyEmit = 0L
        private var lastNotifySig = ""

        fun requestGlobalStop(serviceController: V2TranslationServiceController? = null) {
            sharedEngine?.requestStop()
            runJob?.cancel()
            runJob = null
            notifyJob?.cancel()
            notifyJob = null
            serviceController?.stopService()
        }
    }

    private val repository = DataStoreSettingsRepository(application)
    private val store = SafFileStore(application.applicationContext)
    private val serviceController = V2TranslationServiceController(application.applicationContext)

    val settings: StateFlow<V2Settings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, V2Settings())

    private val engine: RunEngine
        get() {
            return sharedEngine ?: synchronized(V2TranslationViewModel::class.java) {
                sharedEngine ?: RunEngine(store = store, scope = processScope).also { sharedEngine = it }
            }
        }
    val engineState: StateFlow<EngineState> get() = engine.state

    private val _folders = MutableStateFlow<List<V2FolderItem>>(emptyList())
    val folders: StateFlow<List<V2FolderItem>> = _folders.asStateFlow()

    private val _importWarnings = MutableStateFlow<List<String>>(emptyList())
    val importWarnings: StateFlow<List<String>> = _importWarnings.asStateFlow()

    init {
        V2TranslationService.onStopRequested = { requestGlobalStop(serviceController) }
    }

    fun addFolder(uri: Uri, name: String) {
        val key = uri.toString()
        val current = _folders.value
        if (current.any { it.uri == key }) return
        _folders.value = current + V2FolderItem(uri = key, name = name.ifBlank { "選択フォルダ" })
    }

    fun removeFolder(index: Int) {
        val current = _folders.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _folders.value = current
        }
    }

    fun clearFolders() {
        _folders.value = emptyList()
    }

    fun saveSettings(next: V2Settings) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.save(coercedV2Settings(next))
        }
    }

    fun importLegacy(rawJson: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = repository.importLegacy(rawJson)
                _importWarnings.value = result.warnings
                withContext(Dispatchers.IO) {
                    repository.save(result.settings)
                }
            } catch (e: Exception) {
                _importWarnings.value = listOf("旧設定の取込に失敗: ${e.message}")
            }
        }
    }

    fun clearImportWarnings() {
        _importWarnings.value = emptyList()
    }

    fun start() {
        if (engineState.value.isRunning) return
        val current = settings.value
        if (validateV2Settings(current).any { it.blocksSave }) return
        val items = _folders.value.map { it.uri to it.name }
        if (items.isEmpty()) return
        runJob?.cancel()
        notifyJob?.cancel()
        lastNotifyEmit = 0L
        lastNotifySig = ""
        // 即時フォアグラウンド昇格（startForegroundService後の10秒ANR制限内にstartForegroundさせる）
        serviceController.updateNotification(NOTIFICATION_TITLE, "開始準備中...", 0, 0)
        notifyJob = processScope.launch {
            engineState.collect { s ->
                if (!s.isRunning) return@collect
                val (done, total) = s.progress
                val msg = buildProgressMessage(s)
                val sig = "$done/$total|$msg"
                if (sig == lastNotifySig) return@collect
                val now = SystemClock.elapsedRealtime()
                val finished = total > 0 && done >= total
                if (finished || lastNotifySig.isEmpty() || now - lastNotifyEmit >= NOTIFY_THROTTLE_MS) {
                    lastNotifyEmit = now
                    lastNotifySig = sig
                    serviceController.updateNotification(NOTIFICATION_TITLE, msg, done, total)
                }
            }
        }
        runJob = processScope.launch {
            var summary: RunSummary? = null
            try {
                summary = engine.runWithNames(items, current)
            } catch (t: Throwable) {
                // エンジン内部でログ・状態更新済みのため、ここでは通知の後片付けのみ行う
                // 技術的根拠1行：無言吸収を避けるためLogcatに残すが通知後片付けの流れは変えない（外部振る舞い不変）。
                android.util.Log.w("V2ViewModel", "engine run failed", t)
            } finally {
                notifyJob?.cancel()
                notifyJob = null
                finishNotification(summary)
            }
        }
    }

    fun stop() {
        requestGlobalStop(serviceController)
    }

    private fun buildProgressMessage(s: EngineState): String {
        val head = s.statusText.ifBlank { "翻訳を実行中..." }
        val detail = listOf(s.folderName, s.fileName).filter { it.isNotBlank() }.joinToString(" / ")
        val chunk = if (s.chunkProgress.second > 0) " [chunk ${s.chunkProgress.first}/${s.chunkProgress.second}]" else ""
        val full = if (detail.isEmpty()) head + chunk else "$head: $detail$chunk"
        return if (full.length > 120) full.take(120) else full
    }

    private fun finishNotification(summary: RunSummary?) {
        if (summary != null && !summary.aborted) {
            serviceController.showComplete(
                "LLM翻訳完了",
                "${summary.completedFiles}/${summary.totalFiles}ファイル完了"
            )
        } else {
            serviceController.stopService()
        }
    }

    /**
     * 保存前の疎通テスト（単発・bounded）。成功時は応答先頭つきOK、失敗時は分類名を返す。
     * ゲート消費の二重化を避けるためRunEngineのプールとは独立した使い捨て呼び出しとする。
     */
    suspend fun testConnection(profile: V2ModelProfile): String = withContext(Dispatchers.IO) {
        try {
            if (profile.model.isBlank()) return@withContext "NG: モデル名が空です"
            val current = settings.value
            val key = when (profile.providerId.toProviderId()) {
                ProviderId.GEMINI -> current.geminiKeys.firstOrNull { it.isNotBlank() } ?: return@withContext "NG: Geminiキー未設定"
                ProviderId.OPENROUTER -> current.openRouterKey.ifBlank { return@withContext "NG: OpenRouterキー未設定" }
                null -> return@withContext "NG: 未対応プロバイダー"
            }
            // 技術的根拠1行：生成分岐をDefaultHandlerFactoryに一本化し、判定仕様は変えない（外部振る舞い不変）。
            val handler = defaultHandlerFor(profile, key, current)
            val result = handler.call(
                LlmRequest(
                    providerId = profile.providerId,
                    model = profile.model,
                    systemPrompt = "疎通テスト。'ok'とだけ返答してください。",
                    userText = "ping",
                    options = RequestOptions()
                )
            )
            when (result) {
                is LlmResult.Success -> "OK: ${result.text.take(40)}"
                is LlmResult.Failure -> "NG: ${result.failure.kind} ${result.failure.note}".trim()
            }
        } catch (e: Exception) {
            "NG: ${e.message}"
        }
    }

    override fun onCleared() {
        // 技術的根拠1行：画面離脱・Activity再生成時もバックグラウンド翻訳を継続させるため、未実行時のみサービス停止する。
        if (!engineState.value.isRunning) {
            serviceController.stopService()
        }
        super.onCleared()
    }
}
