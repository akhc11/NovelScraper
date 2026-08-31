package com.example.novelscraper

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Google/DeepL 翻訳キューの実行管理（ScrapingViewModel から分離）。
 *
 * 責務:
 * - エンジン別の状態保持（[EngineTranslationState]）とフォルダキュー操作
 * - 複数フォルダ連続翻訳の実行制御（完了で自動遷移）
 * - 待機時間設定の永続化・購読
 * - レースコンディション対策（世代IDによる停止済みコールバックの破棄）
 *
 * UI状態 (MainUiState) への合成は ViewModel 側が本クラスの StateFlow を collect して行う。
 * Service 通知の判断（スクレイピング件数との合算）は ViewModel 側 [onActivityChanged] 経由。
 */
class TranslationQueueManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val repository: PreferencesRepository
) {

    /** 翻訳アクティビティに変化があったことを通知する（ViewModel → Service同期） */
    var onActivityChanged: (() -> Unit)? = null

    private val taskLock = Any()
    @Volatile
    private var googleTask: TranslationTask? = null
    @Volatile
    private var deeplTask: DeeplTranslationTask? = null
    @Volatile
    private var googleSessionId: Long = 0L
    @Volatile
    private var deeplSessionId: Long = 0L

    private val _googleState = MutableStateFlow(EngineTranslationState())
    val googleState: StateFlow<EngineTranslationState> = _googleState.asStateFlow()

    private val _deeplState = MutableStateFlow(
        EngineTranslationState(chunkDelay = "3-8", fileDelay = "2-5")
    )
    val deeplState: StateFlow<EngineTranslationState> = _deeplState.asStateFlow()

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
    }

    // ---- キュー操作 ----

    fun updateDelays(engine: TranslationEngine, chunkDelay: String, fileDelay: String) {
        scope.launch {
            when (engine) {
                TranslationEngine.GOOGLE -> repository.saveGoogleDelays(chunkDelay, fileDelay)
                TranslationEngine.DEEPL -> repository.saveDeeplDelays(chunkDelay, fileDelay)
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
            Toast.makeText(appContext, "翻訳対象のフォルダを選択してください", Toast.LENGTH_SHORT).show()
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

        // 複数フォルダキューの先頭から開始
        startNextFolderInQueue(engine, 0, currentSessionId)
    }

    /**
     * 複数フォルダ連続翻訳キューの実行制御。
     * 現在のフォルダの全話が完了したら自動的に次のフォルダへ進む。
     */
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
            // 全フォルダの連続翻訳が完了
            mutate(engine) {
                it.copy(isTranslating = false, statusText = "全 ${folders.size} フォルダの翻訳が完了しました")
            }
            val engineName = if (engine == TranslationEngine.GOOGLE) "Google" else "DeepL"
            Toast.makeText(appContext, "[$engineName 翻訳] 全 ${folders.size} フォルダの翻訳が完了しました", Toast.LENGTH_LONG).show()
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

        when (engine) {
            TranslationEngine.GOOGLE -> {
                val task = TranslationTask(
                    context = appContext,
                    folderUri = targetUri,
                    sourceLang = engineState.sourceLang,
                    targetLang = engineState.targetLang,
                    chunkDelay = engineState.chunkDelay,
                    fileDelay = engineState.fileDelay,
                    listener = object : TranslationTask.TranslationListener {
                        override fun onProgress(
                            completedFiles: Int,
                            totalFiles: Int,
                            currentFileName: String,
                            currentChunk: Int,
                            totalChunks: Int,
                            statusText: String
                        ) {
                            synchronized(taskLock) {
                                if (sessionId != googleSessionId) return
                            }
                            _googleState.update {
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
                                if (sessionId == googleSessionId) {
                                    googleTask = null
                                    success && _googleState.value.isTranslating
                                } else {
                                    false
                                }
                            }
                            if (shouldProceed && folderIndex + 1 < folders.size) {
                                // 次のフォルダへ自動遷移
                                startNextFolderInQueue(engine, folderIndex + 1, sessionId)
                            } else if (shouldProceed) {
                                _googleState.update {
                                    it.copy(isTranslating = false, statusText = "${folderProgressPrefix}$message")
                                }
                                Toast.makeText(appContext, "[Google翻訳] $message", Toast.LENGTH_LONG).show()
                                notifyChanged()
                            }
                        }
                    }
                )
                synchronized(taskLock) {
                    if (sessionId != googleSessionId) return
                    googleTask?.stop()
                    googleTask = task
                }
                task.start()
            }
            TranslationEngine.DEEPL -> {
                val task = DeeplTranslationTask(
                    context = appContext,
                    folderUri = targetUri,
                    sourceLang = engineState.sourceLang,
                    targetLang = engineState.targetLang,
                    chunkDelay = engineState.chunkDelay,
                    fileDelay = engineState.fileDelay,
                    listener = object : TranslationTask.TranslationListener {
                        override fun onProgress(
                            completedFiles: Int,
                            totalFiles: Int,
                            currentFileName: String,
                            currentChunk: Int,
                            totalChunks: Int,
                            statusText: String
                        ) {
                            synchronized(taskLock) {
                                if (sessionId != deeplSessionId) return
                            }
                            _deeplState.update {
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
                                if (sessionId == deeplSessionId) {
                                    deeplTask = null
                                    success && _deeplState.value.isTranslating
                                } else {
                                    false
                                }
                            }
                            if (shouldProceed && folderIndex + 1 < folders.size) {
                                startNextFolderInQueue(engine, folderIndex + 1, sessionId)
                            } else if (shouldProceed) {
                                _deeplState.update {
                                    it.copy(isTranslating = false, statusText = "${folderProgressPrefix}$message")
                                }
                                Toast.makeText(appContext, "[DeepL翻訳] $message", Toast.LENGTH_LONG).show()
                                notifyChanged()
                            }
                        }
                    }
                )
                synchronized(taskLock) {
                    if (sessionId != deeplSessionId) return
                    deeplTask?.stop()
                    deeplTask = task
                }
                task.start()
            }
        }
    }

    fun stop(engine: TranslationEngine) {
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
            }
        }
        notifyChanged()
    }

    /** ViewModel 破棄時の後始末（状態文言は更新しない） */
    fun shutdown() {
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
        if (engine == TranslationEngine.GOOGLE) _googleState.value else _deeplState.value

    private fun mutate(engine: TranslationEngine, f: (EngineTranslationState) -> EngineTranslationState) {
        when (engine) {
            TranslationEngine.GOOGLE -> _googleState.update(f)
            TranslationEngine.DEEPL -> _deeplState.update(f)
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
