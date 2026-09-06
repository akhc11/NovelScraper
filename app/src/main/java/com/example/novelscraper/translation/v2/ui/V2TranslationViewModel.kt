package com.example.novelscraper.translation.v2.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.RequestOptions
import com.example.novelscraper.translation.v2.engine.EngineState
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.infra.GeminiHandler
import com.example.novelscraper.translation.v2.infra.OpenRouterHandler
import com.example.novelscraper.translation.v2.infra.SafFileStore
import com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class V2FolderItem(val uri: String, val name: String)

/**
 * v2 UI状態・イベント中継のみ（判定・巡回・保存判定は下位層）。
 * 技術的根拠1行：重い実行・保存はDispatchers.IO＋viewModelScopeに寄せ、旧TranslationQueueManagerと二重管理しない。
 */
class V2TranslationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataStoreSettingsRepository(application)
    private val store = SafFileStore(application.applicationContext)

    val settings: StateFlow<V2Settings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, V2Settings())

    private val engine = RunEngine(store = store, scope = viewModelScope)
    val engineState: StateFlow<EngineState> = engine.state

    private val _folders = MutableStateFlow<List<V2FolderItem>>(emptyList())
    val folders: StateFlow<List<V2FolderItem>> = _folders.asStateFlow()

    private val _importWarnings = MutableStateFlow<List<String>>(emptyList())
    val importWarnings: StateFlow<List<String>> = _importWarnings.asStateFlow()

    private var runJob: Job? = null

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
        val uris = _folders.value.map { it.uri }
        if (uris.isEmpty()) return
        runJob?.cancel()
        runJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.run(uris, current)
            } catch (_: Exception) {
                // 中断・失敗の詳細はEngineStateログ側に集約する
            }
        }
    }

    fun stop() {
        engine.requestStop()
        runJob?.cancel()
    }

    /**
     * 保存前の疎通テスト（単発・bounded）。成功時は使用量つきOK、失敗時は分類名を返す。
     * ゲート消費の二重化を避けるためRunEngineのプールとは独立した使い捨て呼び出しとする。
     */
    suspend fun testConnection(profile: V2ModelProfile): String = withContext(Dispatchers.IO) {
        try {
            if (profile.model.isBlank()) return@withContext "NG: モデル名が空です"
            val current = settings.value
            val key = when (profile.providerId) {
                "gemini" -> current.geminiKeys.firstOrNull { it.isNotBlank() } ?: return@withContext "NG: Geminiキー未設定"
                "openrouter" -> current.openRouterKey.ifBlank { return@withContext "NG: OpenRouterキー未設定" }
                else -> return@withContext "NG: 未対応プロバイダー"
            }
            val handler = when (profile.providerId) {
                "gemini" -> GeminiHandler(apiKey = key)
                else -> OpenRouterHandler(
                    apiKey = key,
                    endpoint = current.openRouterEndpoint,
                    reasoningEffort = profile.reasoningEffort,
                    reasoningEnabled = profile.reasoningEnabled,
                    providerOrder = profile.providerOrder,
                    providerAllowFallbacks = profile.providerAllowFallbacks
                )
            }
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
        runJob?.cancel()
        super.onCleared()
    }
}
