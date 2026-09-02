package com.example.novelscraper

import android.content.Context
import android.net.Uri
import com.example.novelscraper.translation.llm.engine.LlmEngineState
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.LlmTranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Google/DeepL/LLM(AI) 翻訳キューの実行管理（ScrapingViewModel から分離）。
 */
class TranslationQueueManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val repository: PreferencesRepository
) {

    /** 翻訳アクティビティに変化があったことを通知する（ViewModel → Service同期） */
    var onActivityChanged: (() -> Unit)? = null

    /** UI通知メッセージの発行コールバック（message, isLong） */
    var onShowMessage: ((String, Boolean) -> Unit)? = null

    private val taskLock = Any()
    @Volatile
    private var googleTask: BaseWebTranslationTask? = null
    @Volatile
    private var deeplTask: BaseWebTranslationTask? = null
    @Volatile
    private var googleSessionId: Long = 0L
    @Volatile
    private var deeplSessionId: Long = 0L

    val llmEngine: LlmTranslationEngine = LlmTranslationEngine(appContext, scope)

    private val _googleState = MutableStateFlow(EngineTranslationState())
    val googleState: StateFlow<EngineTranslationState> = _googleState.asStateFlow()

    private val _deeplState = MutableStateFlow(
        EngineTranslationState(chunkDelay = "3-8", fileDelay = "2-5")
    )
    val deeplState: StateFlow<EngineTranslationState> = _deeplState.asStateFlow()

    private val _llmState = MutableStateFlow(
        EngineTranslationState(chunkDelay = "2", fileDelay = "2")
    )
    val llmState: StateFlow<EngineTranslationState> = _llmState.asStateFlow()

    init {
        // 待機時間設定の購読（DataStore の保存値を常に反映）
        scope.launch {
            repository.googleChunkDelayFlow.collect { d -> _googleState.update { it.copy(chunkDelay = d) } }
        }
        scope.launch {
            repository.googleFileDelayFlow.collect { d -> _googleState.update { it.copy(fileDelay = d) } }
        }
        scope.launch {
            repository.deeplChunkDelayFlow.collect { d -> _deeplState.update { it.copy(chunkDelay = d) } }
        }
        scope.launch {
            repository.deeplFileDelayFlow.collect { d -> _deeplState.update { it.copy(fileDelay = d) } }
        }

        // LLMエンジンの内部ライブ状態を購読して同期
        scope.launch {
            llmEngine.engineState.collect { live ->
                _llmState.update { st ->
                    st.copy(
                        isTranslating = live.isTranslating,
                        statusText = live.statusText,
                        progress = live.progress,
                        currentFileName = live.currentFileName,
                        chunkProgress = live.chunkProgress
                    )
                }
                notifyChanged()
            }
        }
    }

    // ---- キュー操作 ----

    fun updateDelays(engine: TranslationEngine, chunkDelay: String, fileDelay: String) {
        scope.launch {
            when (engine) {
                TranslationEngine.GOOGLE -> repository.saveGoogleDelays(chunkDelay, fileDelay)
                TranslationEngine.DEEPL -> repository.saveDeeplDelays(chunkDelay, fileDelay)
                TranslationEngine.LLM_API -> {}
            }
            mutate(engine) { it.copy(chunkDelay = chunkDelay, fileDelay = fileDelay) }
        }
    }

    fun addFolder(engine: TranslationEngine, uri: Uri, folderName: String) {
        val newItem = FolderItem(
            path = uri.path ?: uri.toString(),
            name = folderName,
            uri = uri
        )
        mutate(engine) { st ->
            val updatedList = if (st.selectedFolders.any { it.uri == uri || (it.path.isNotEmpty() && it.path == newItem.path) }) {
                st.selectedFolders
            } else {
                st.selectedFolders + newItem
            }
            st.copy(
                selectedFolders = updatedList,
                folderUri = updatedList.firstOrNull()?.uri,
                folderName = displayName(updatedList)
            )
        }
    }

    fun removeFolder(engine: TranslationEngine, index: Int) {
        mutate(engine) { st ->
            val updatedList = st.selectedFolders.toMutableList().apply {
                if (index in indices) removeAt(index)
            }
            st.copy(
                selectedFolders = updatedList,
                folderUri = updatedList.firstOrNull()?.uri,
                folderName = displayName(updatedList)
            )
        }
    }

    fun clearFolders(engine: TranslationEngine) {
        mutate(engine) { it.copy(selectedFolders = emptyList(), folderUri = null, folderName = "") }
    }

    // ---- 実行制御 ----

    fun start(engine: TranslationEngine) {
        val engineState = stateFor(engine)

        if (engineState.selectedFolders.isEmpty() && engineState.folderUri == null) {
            onShowMessage?.invoke("翻訳対象のフォルダを選択してください", false)
            return
        }

        if (engine == TranslationEngine.LLM_API) {
            val uris = engineState.selectedFolders.mapNotNull { it.uri }.ifEmpty {
                engineState.folderUri?.let { listOf(it) } ?: emptyList()
            }
            llmEngine.startTranslation(uris) {
                onShowMessage?.invoke("[AI/LLM 翻訳] 全フォルダの処理が完了しました", true)
                notifyChanged()
            }
            return
        }

        if (engineState.isTranslating) return

        val currentSessionId = synchronized(taskLock) {
            if (engine == TranslationEngine.GOOGLE) {
                ++googleSessionId
            } else {
                ++deeplSessionId
            }
        }

        startNextFolderInQueue(engine, 0, currentSessionId)
    }

    private fun startNextFolderInQueue(engine: TranslationEngine, folderIndex: Int, sessionId: Long) {
        synchronized(taskLock) {
            val activeSessionId = if (engine == TranslationEngine.GOOGLE) googleSessionId else deeplSessionId
            if (sessionId != activeSessionId) return
        }

        val engineState = stateFor(engine)

        val folders = engineState.selectedFolders.ifEmpty {
            if (engineState.folderUri != null) {
                listOf(FolderItem(path = engineState.folderUri.path ?: "", name = engineState.folderName, uri = engineState.folderUri))
            } else emptyList()
        }

        if (folders.isEmpty() || folderIndex >= folders.size) {
            mutate(engine) {
                it.copy(isTranslating = false, statusText = "全 ${folders.size} フォルダの翻訳が完了しました")
            }
            val engineName = if (engine == TranslationEngine.GOOGLE) "Google" else "DeepL"
            onShowMessage?.invoke("[$engineName 翻訳] 全 ${folders.size} フォルダの翻訳が完了しました", true)
            notifyChanged()
            return
        }

        val currentItem = folders[folderIndex]
        val targetUri = currentItem.uri ?: Uri.fromFile(File(currentItem.path))
        val folderProgressPrefix = if (folders.size > 1) "[フォルダ ${folderIndex + 1}/${folders.size}] " else ""

        mutate(engine) {
            it.copy(
                currentFolderIndex = folderIndex,
                folderUri = targetUri,
                folderName = if (folders.size == 1) currentItem.name else "${currentItem.name} (${folderIndex + 1}/${folders.size})",
                isTranslating = true,
                statusText = "${folderProgressPrefix}${currentItem.name} を開始中...",
                progress = Pair(0, 0),
                chunkProgress = Pair(0, 0)
            )
        }
        notifyChanged()

        val listener = object : BaseWebTranslationTask.TranslationListener {
            override fun onProgress(
                completedFiles: Int,
                totalFiles: Int,
                currentFileName: String,
                currentChunk: Int,
                totalChunks: Int,
                statusText: String
            ) {
                synchronized(taskLock) {
                    val activeSessionId = if (engine == TranslationEngine.GOOGLE) googleSessionId else deeplSessionId
                    if (sessionId != activeSessionId) return
                }
                mutate(engine) {
                    it.copy(
                        progress = Pair(completedFiles, totalFiles),
                        currentFileName = currentFileName,
                        chunkProgress = Pair(currentChunk, totalChunks),
                        statusText = "${folderProgressPrefix}$statusText"
                    )
                }
                notifyChanged()
            }

            override fun onTaskFinished(success: Boolean, message: String) {
                val shouldProceed = synchronized(taskLock) {
                    val activeSessionId = if (engine == TranslationEngine.GOOGLE) googleSessionId else deeplSessionId
                    if (sessionId == activeSessionId) {
                        if (engine == TranslationEngine.GOOGLE) googleTask = null else deeplTask = null
                        success && stateFor(engine).isTranslating
                    } else {
                        false
                    }
                }
                if (shouldProceed && folderIndex + 1 < folders.size) {
                    startNextFolderInQueue(engine, folderIndex + 1, sessionId)
                } else if (shouldProceed) {
                    mutate(engine) {
                        it.copy(isTranslating = false, statusText = "${folderProgressPrefix}$message")
                    }
                    val engineName = if (engine == TranslationEngine.GOOGLE) "Google" else "DeepL"
                    onShowMessage?.invoke("[$engineName 翻訳] $message", true)
                    notifyChanged()
                }
            }
        }

        val strategy: WebTranslationStrategy = when (engine) {
            TranslationEngine.GOOGLE -> GoogleTranslationStrategy()
            TranslationEngine.DEEPL -> DeeplTranslationStrategy()
            TranslationEngine.LLM_API -> GoogleTranslationStrategy() // WebTask用フォールバック
        }

        val task = BaseWebTranslationTask(
            context = appContext,
            folderUri = targetUri,
            strategy = strategy,
            sourceLang = engineState.sourceLang,
            targetLang = engineState.targetLang,
            chunkDelay = engineState.chunkDelay,
            fileDelay = engineState.fileDelay,
            listener = listener
        )

        synchronized(taskLock) {
            val activeSessionId = if (engine == TranslationEngine.GOOGLE) googleSessionId else deeplSessionId
            if (sessionId != activeSessionId) return
            if (engine == TranslationEngine.GOOGLE) {
                googleTask?.stop()
                googleTask = task
            } else {
                deeplTask?.stop()
                deeplTask = task
            }
        }
        task.start()
    }

    fun stop(engine: TranslationEngine) {
        if (engine == TranslationEngine.LLM_API) {
            llmEngine.stopTranslation()
            return
        }
        synchronized(taskLock) {
            when (engine) {
                TranslationEngine.GOOGLE -> {
                    googleSessionId++
                    val task = googleTask
                    googleTask = null
                    task?.stop()
                    _googleState.update {
                        it.copy(isTranslating = false, statusText = "Google翻訳を停止しました")
                    }
                }
                TranslationEngine.DEEPL -> {
                    deeplSessionId++
                    val task = deeplTask
                    deeplTask = null
                    task?.stop()
                    _deeplState.update {
                        it.copy(isTranslating = false, statusText = "DeepL翻訳を停止しました")
                    }
                }
                TranslationEngine.LLM_API -> {}
            }
        }
        notifyChanged()
    }

    fun shutdown() {
        llmEngine.stopTranslation()
        synchronized(taskLock) {
            googleSessionId++
            deeplSessionId++

            val gTask = googleTask
            googleTask = null
            gTask?.stop()

            val dTask = deeplTask
            deeplTask = null
            dTask?.stop()
        }
    }

    // ---- 内部ユーティリティ ----

    private fun stateFor(engine: TranslationEngine): EngineTranslationState =
        when (engine) {
            TranslationEngine.GOOGLE -> _googleState.value
            TranslationEngine.DEEPL -> _deeplState.value
            TranslationEngine.LLM_API -> _llmState.value
        }

    private fun mutate(engine: TranslationEngine, f: (EngineTranslationState) -> EngineTranslationState) {
        when (engine) {
            TranslationEngine.GOOGLE -> _googleState.update(f)
            TranslationEngine.DEEPL -> _deeplState.update(f)
            TranslationEngine.LLM_API -> _llmState.update(f)
        }
    }

    private fun displayName(list: List<FolderItem>): String = when (list.size) {
        0 -> ""
        1 -> list.first().name
        else -> "${list.first().name} (他${list.size - 1}件)"
    }

    private fun notifyChanged() {
        onActivityChanged?.invoke()
    }
}