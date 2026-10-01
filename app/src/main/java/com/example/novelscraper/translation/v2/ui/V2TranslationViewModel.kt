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
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.engine.EngineState
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.engine.RunSummary
import com.example.novelscraper.translation.v2.engine.canStartRun
import com.example.novelscraper.translation.v2.engine.defaultHandlerFor
import com.example.novelscraper.translation.v2.infra.SafFileStore
import com.example.novelscraper.translation.picker.FALLBACK_FOLDER_NAME
import com.example.novelscraper.translation.v2.service.TranslationDiagnostics
import com.example.novelscraper.translation.v2.service.V2TranslationService
import com.example.novelscraper.translation.v2.service.V2TranslationServiceController
import com.example.novelscraper.translation.v2.settings.DataStorePresetRepository
import com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository
import com.example.novelscraper.translation.v2.settings.PresetRepository
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PresetIndexEntry
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.applyPresetSnapshot
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

data class V2FolderItem(val uri: String, val name: String, val fileUris: Set<String> = emptySet())

/**
 * v2 UI状態・イベント中継と開始前ガード（判定・巡回・保存判定の本体は下位層）。
 * 技術的根拠1行：重い実行・保存はDispatchers.IO＋viewModelScopeに寄せ、旧TranslationQueueManagerと二重管理しない。
 * フォアグラウンド通知の責務もここに集約する（開始直後に昇格→進捗追従→完了/停止で降格）。
 * 注意: 既定値付き注入はviewModel()既定factoryが単一Application引数しか解決できず実行時死するため採用しない。
 * 技術的根拠1行：試験性は純粋関数(RunLedger)の抽出で確保し、VM接着部のfactory追加はしない。
 */
class V2TranslationViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val NOTIFY_THROTTLE_MS = 1500L

        // 技術的根拠1行：Activity破棄時のonClearedによる翻訳強制中断を防ぎフォアグラウンドサービスと共に完走させるためプロセス生存スコープで実行する。
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** 並行run共有の送信ゲート。合計並列の上限だけ共有し、他はrun別に保つ。 */
        private val sharedSendGate = V2SendGate()
    }

    private data class RunHolder(
        val runId: String,
        val slot: Int,
        val label: String,
        val engine: RunEngine,
        val folderUris: Set<String>,
        var runJob: Job? = null,
        var watcherJob: Job? = null,
        var lastNotifyEmit: Long = 0L,
        var lastNotifySig: String = ""
    )

    private val repository = DataStoreSettingsRepository(application)
    private val presetRepository: PresetRepository = DataStorePresetRepository(application)
    private val store = SafFileStore(application.applicationContext)
    private val serviceController = V2TranslationServiceController(application.applicationContext)

    /** 同時実行の保持。processScope完了路とviewModelScope操作路で触るためrunLockで守る。 */
    private val runLock = Any()
    private val activeRuns = mutableMapOf<String, RunHolder>()

    val settings: StateFlow<V2Settings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, V2Settings())

    /** プリセット索引のみ（軽量）。全文は適用時に1件だけ読む。 */
    val presetIndex: StateFlow<List<V2PresetIndexEntry>> = presetRepository.index
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _presetMessage = MutableStateFlow("")
    val presetMessage: StateFlow<String> = _presetMessage.asStateFlow()

    private val _runMessage = MutableStateFlow("")
    val runMessage: StateFlow<String> = _runMessage.asStateFlow()

    /** 最後に適用したプリセット名。実行の表示名に使う（空＝時刻のみ表示）。 */
    private var lastPresetLabel: String = ""

    /** runId→最新EngineState（実行中・直近完了）。上限で縛るため無制限に育たない。 */
    private val _runStates = MutableStateFlow<Map<String, EngineState>>(emptyMap())
    private val _runMeta = MutableStateFlow<Map<String, Pair<String, Int>>>(emptyMap())

    /** 画面用の一覧（実行中優先）。 */
    private val _runs = MutableStateFlow<List<RunView>>(emptyList())
    val runs: StateFlow<List<RunView>> = _runs.asStateFlow()

    /** MainScreen用の集約（いずれか実行中か・最新文面）。 */
    private val _engineAgg = MutableStateFlow(EngineState())
    val engineState: StateFlow<EngineState> = _engineAgg.asStateFlow()

    private fun refreshRuns() {
        val active = synchronized(runLock) { activeRuns.keys.toSet() }
        _runs.value = buildRunViews(_runStates.value, _runMeta.value, active)
        val next = aggregateEngineState(_runs.value)
        // 技術的根拠1行：集約の消費者はisRunning・文面のみのため、変わらない発火でMainの再構成を作らない。
        val prev = _engineAgg.value
        if (prev.isRunning != next.isRunning || prev.statusText != next.statusText) {
            _engineAgg.value = next
        }
    }

    private fun doneTitle(label: String): String =
        if (label.isBlank()) "LLM翻訳完了" else "LLM翻訳完了・$label"

    private val _folders = MutableStateFlow<List<V2FolderItem>>(emptyList())
    val folders: StateFlow<List<V2FolderItem>> = _folders.asStateFlow()

    private val _importWarnings = MutableStateFlow<List<String>>(emptyList())
    val importWarnings: StateFlow<List<String>> = _importWarnings.asStateFlow()

    private fun notifyTitle(label: String): String =
        if (label.isBlank()) "LLM小説翻訳" else "LLM小説翻訳・$label"

    fun addFolder(uri: Uri, name: String, fileUris: Set<String> = emptySet()) {
        val key = uri.toString()
        val current = _folders.value
        val existing = current.firstOrNull { it.uri == key }
        if (existing != null) {
            // 技術的根拠1行：同一フォルダへの後足しは和集合に寄せ、選択スナップショットの欠落を作らない。
            if (fileUris.isEmpty() || existing.fileUris.isEmpty()) {
                if (existing.fileUris.isNotEmpty() && fileUris.isEmpty()) {
                    _folders.value = current.map { if (it.uri == key) it.copy(fileUris = emptySet()) else it }
                }
                return
            }
            val merged = existing.fileUris + fileUris
            if (merged != existing.fileUris) {
                _folders.value = current.map { if (it.uri == key) it.copy(fileUris = merged) else it }
            }
            return
        }
        _folders.value = current + V2FolderItem(uri = key, name = name.ifBlank { FALLBACK_FOLDER_NAME }, fileUris = fileUris)
    }

    /** 自前ブラウザ確定結果の一括追加(加算のみ。既存単発追加は不変)。 */
    fun addFolderEntries(entries: List<Pair<String, String>>) {
        for ((uriString, name) in entries) {
            if (uriString.isBlank()) continue
            addFolder(Uri.parse(uriString), name)
        }
    }

    /** 自前ブラウザ確定結果の一括追加(絞り込み付き。空fileUrisは全件)。 */
    fun addFolderTargets(targets: List<com.example.novelscraper.translation.picker.V2FolderTarget>) {
        for (t in targets) {
            if (t.folderUri.isBlank()) continue
            addFolder(Uri.parse(t.folderUri), t.displayName, t.fileUris)
        }
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

    /**
     * 全設定プリセット操作（重いJSON処理はIOに寄せる）。
     * 技術的根拠1行：索引は常駐・実体は単発読込にし、適用時は現行の鍵を維持してBYOKを守る。
     */
    fun savePreset(label: String, settings: V2Settings) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = presetRepository.save(label, coercedV2Settings(settings))
            _presetMessage.value = if (id != null) "プリセットを保存しました" else "上限のため保存できませんでした"
        }
    }

    fun applyPreset(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val preset = presetRepository.load(id)
            if (preset == null) {
                _presetMessage.value = "プリセットの読込に失敗しました"
                return@launch
            }
            repository.save(coercedV2Settings(applyPresetSnapshot(settings.value, preset.snapshot)))
            lastPresetLabel = preset.label
            _presetMessage.value = "プリセット「${preset.label}」を適用しました"
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            presetRepository.delete(id)
            _presetMessage.value = "プリセットを削除しました"
        }
    }

    fun clearPresetMessage() {
        _presetMessage.value = ""
    }

    fun start() {
        val current = settings.value
        if (validateV2Settings(current).any { it.blocksSave }) return
        val folders = _folders.value
        if (folders.isEmpty()) return
        val newUris = folders.map { it.uri }.toSet()
        // 技術的根拠1行：上限・重複の判定は純粋関数に寄せ、UIとテストで同一判定を使う。
        val guard = synchronized(runLock) {
            canStartRun(
                activeRuns.size,
                TranslationLimits.MAX_CONCURRENT_RUNS,
                activeRuns.values.flatMap { it.folderUris }.toSet(),
                newUris
            )
        }
        if (guard != null) {
            _runMessage.value = guard
            return
        }
        val runId = "run-" + System.currentTimeMillis()
        val slot = synchronized(runLock) {
            nextFreeSlot(activeRuns.values.map { it.slot }.toSet(), TranslationLimits.MAX_CONCURRENT_RUNS)
        }
        val label = lastPresetLabel
        val items = folders.map {
            com.example.novelscraper.translation.v2.engine.FolderTarget(it.uri, it.name, it.fileUris)
        }
        val engine = RunEngine(store = store, scope = processScope, sharedSendGate = sharedSendGate)
        val holder = RunHolder(runId, slot, label, engine, newUris)
        synchronized(runLock) {
            activeRuns[runId] = holder
            // 終了済み世代は新開始時に捨て、runStatesの無制限肥大を作らない。
            _runStates.value = pruneToIds(_runStates.value, activeRuns.keys)
            _runMeta.value = pruneToIds(_runMeta.value, activeRuns.keys) + (runId to (label to slot))
        }
        refreshRuns()
        // 技術的根拠1行：通知停止は使い捨てのため実行毎に登録し直し、2回目以降の連投でも停止可能にする。
        V2TranslationService.stopCallbacks[runId] = { stopRun(runId) }
        // 技術的根拠1行：実行マーカーは開始確定後にだけ立て、検証脱落時は中断誤検知を作らない。
        TranslationDiagnostics.markRunning(getApplication(), true, "run=$runId folders=${items.size}")
        TranslationDiagnostics.appendLine(getApplication(), "lifecycle", "run started ($runId ${label.ifBlank { "-" }} folders=${items.size})")
        // 即時フォアグラウンド昇格（startForegroundService後の10秒ANR制限内にstartForegroundさせる）
        serviceController.updateNotification(notifyTitle(label), "開始準備中...", 0, 0, runId, slot)
        holder.watcherJob = processScope.launch {
            engine.state.collect { s ->
                synchronized(runLock) {
                    _runStates.value = _runStates.value + (runId to s)
                }
                refreshRuns()
                if (!s.isRunning) return@collect
                val (done, total) = s.progress
                val msg = buildProgressMessage(s)
                val sig = "$done/$total|$msg"
                if (sig == holder.lastNotifySig) return@collect
                val now = SystemClock.elapsedRealtime()
                val finished = total > 0 && done >= total
                if (finished || holder.lastNotifySig.isEmpty() || now - holder.lastNotifyEmit >= NOTIFY_THROTTLE_MS) {
                    holder.lastNotifyEmit = now
                    holder.lastNotifySig = sig
                    serviceController.updateNotification(notifyTitle(label), msg, done, total, runId, slot)
                }
            }
        }
        holder.runJob = processScope.launch {
            var summary: RunSummary? = null
            try {
                summary = engine.runWithTargets(items, current)
            } catch (t: Throwable) {
                // エンジン内部でログ・状態更新済みのため、ここでは通知の後片付けのみ行う
                // 技術的根拠1行：無言吸収を避けるためLogcatに残すが通知後片付けの流れは変えない（外部振る舞い不変）。
                android.util.Log.w("V2ViewModel", "engine run failed", t)
            } finally {
                finishRun(runId, summary)
            }
        }
    }

    /** run単体の後片付け。processScope完了路からも呼ばれるためrunLockで守る。 */
    private fun finishRun(runId: String, summary: RunSummary?) {
        val holder = synchronized(runLock) { activeRuns.remove(runId) }
        holder?.watcherJob?.cancel()
        V2TranslationService.stopCallbacks.remove(runId)
        if (holder != null) {
            TranslationDiagnostics.appendLine(
                getApplication(), "lifecycle",
                "run finished ($runId completed=${summary?.completedFiles}/${summary?.totalFiles} aborted=${summary?.aborted})"
            )
            if (summary != null && !summary.aborted) {
                serviceController.showComplete(
                    doneTitle(holder.label),
                    "${summary.completedFiles}/${summary.totalFiles}ファイル完了",
                    holder.slot
                )
            } else {
                serviceController.removeRunNotification(holder.slot)
            }
        }
        val anyActive = synchronized(runLock) { activeRuns.isNotEmpty() }
        TranslationDiagnostics.markRunning(getApplication(), anyActive)
        if (!anyActive) {
            serviceController.stopService()
        }
        refreshRuns()
    }

    fun stopRun(runId: String) {
        val holder = synchronized(runLock) { activeRuns[runId] } ?: return
        TranslationDiagnostics.appendLine(getApplication(), "lifecycle", "run stopped by user ($runId)")
        holder.engine.requestStop()
        holder.runJob?.cancel()
        holder.watcherJob?.cancel()
    }

    fun stop() {
        val ids = synchronized(runLock) { activeRuns.keys.toList() }
        if (ids.isEmpty()) {
            TranslationDiagnostics.markRunning(getApplication(), false)
            serviceController.stopService()
            return
        }
        TranslationDiagnostics.markRunning(getApplication(), false)
        TranslationDiagnostics.appendLine(getApplication(), "lifecycle", "all runs stopped by user")
        ids.forEach { stopRun(it) }
    }

    fun clearRunMessage() {
        _runMessage.value = ""
    }

    private fun buildProgressMessage(s: EngineState): String {
        val head = s.statusText.ifBlank { "翻訳を実行中..." }
        val detail = listOf(s.folderName, s.fileName).filter { it.isNotBlank() }.joinToString(" / ")
        val chunk = if (s.chunkProgress.second > 0) " [chunk ${s.chunkProgress.first}/${s.chunkProgress.second}]" else ""
        val full = if (detail.isEmpty()) head + chunk else "$head: $detail$chunk"
        return if (full.length > 120) full.take(120) else full
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
