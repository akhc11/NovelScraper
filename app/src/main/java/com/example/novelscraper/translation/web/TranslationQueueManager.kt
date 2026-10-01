package com.example.novelscraper.translation.web

import com.example.novelscraper.*

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.common.NovelPhysicalSplitter
import com.example.novelscraper.translation.common.ingest.DeclaredEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Google/DeepL/Papago/LLM(AI) 翻訳キューの実行管理（ScrapingViewModel から分離）。
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
    private var papagoTask: BaseWebTranslationTask? = null
    @Volatile
    private var googleSessionId: Long = 0L
    @Volatile
    private var deeplSessionId: Long = 0L
    @Volatile
    private var papagoSessionId: Long = 0L

    private val _googleState = MutableStateFlow(EngineTranslationState())
    val googleState: StateFlow<EngineTranslationState> = _googleState.asStateFlow()

    private val _deeplState = MutableStateFlow(
        EngineTranslationState(chunkDelay = "3-8", fileDelay = "2-5")
    )
    val deeplState: StateFlow<EngineTranslationState> = _deeplState.asStateFlow()

    private val _papagoState = MutableStateFlow(
        EngineTranslationState(chunkDelay = "3-8", fileDelay = "2-5", sourceLang = "ko")
    )
    val papagoState: StateFlow<EngineTranslationState> = _papagoState.asStateFlow()

    private val _llmState = MutableStateFlow(
        EngineTranslationState(chunkDelay = "2", fileDelay = "2")
    )
    val llmState: StateFlow<EngineTranslationState> = _llmState.asStateFlow()

    val isWebSplitEnabled: StateFlow<Boolean> = repository.isWebSplitEnabledFlow
        .stateIn(scope, SharingStarted.Eagerly, false)

    val webSplitSizeChars: StateFlow<Int> = repository.webSplitSizeCharsFlow
        .stateIn(scope, SharingStarted.Eagerly, 8000)

    val inputEncoding: StateFlow<DeclaredEncoding?> = repository.inputEncodingFlow
        .map { DeclaredEncoding.parseOrNull(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

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
        scope.launch {
            repository.papagoChunkDelayFlow.collect { d -> _papagoState.update { it.copy(chunkDelay = d) } }
        }
        scope.launch {
            repository.papagoFileDelayFlow.collect { d -> _papagoState.update { it.copy(fileDelay = d) } }
        }

    }

    // ---- 設定・キュー操作 ----

    fun toggleWebSplit(enabled: Boolean) {
        scope.launch {
            repository.saveWebSplitEnabled(enabled)
        }
    }

    fun updateWebSplitSizeChars(sizeChars: Int) {
        scope.launch {
            repository.saveWebSplitSizeChars(sizeChars)
        }
    }

    fun updateInputEncoding(value: String) {
        scope.launch {
            repository.saveInputEncoding(value)
        }
    }

    fun updateDelays(engine: TranslationEngine, chunkDelay: String, fileDelay: String) {
        scope.launch {
            when (engine) {
                TranslationEngine.GOOGLE -> repository.saveGoogleDelays(chunkDelay, fileDelay)
                TranslationEngine.DEEPL -> repository.saveDeeplDelays(chunkDelay, fileDelay)
                TranslationEngine.PAPAGO -> repository.savePapagoDelays(chunkDelay, fileDelay)
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
            return
        }

        if (engineState.isTranslating) return

        val currentSessionId = synchronized(taskLock) {
            when (engine) {
                TranslationEngine.GOOGLE -> ++googleSessionId
                TranslationEngine.DEEPL -> ++deeplSessionId
                TranslationEngine.PAPAGO -> ++papagoSessionId
                TranslationEngine.LLM_API -> 0L
            }
        }

        // Mainでオーケストレーションし、阻塞SAF処理のみwithContext(IO)に分離する。
        // BaseWebTranslationTask（WebView生成）はMainスレッド必須のため、launch先をIOにしてはならない。
        scope.launch {
            val foldersToProcess = engineState.selectedFolders.ifEmpty {
                engineState.folderUri?.let { listOf(FolderItem(path = it.path ?: "", name = engineState.folderName, uri = it)) } ?: emptyList()
            }

            // オンデマンド分割の準備: フォルダ直下に生テキストファイルがある場合、ファイル単位のキュー項目として即座に展開 (中身の分割はここでは行わないため 0.05秒で完了)
            if (isWebSplitEnabled.value) {
                val newFolderList = mutableListOf<FolderItem>()
                var foundRawText = false

                for (f in foldersToProcess) {
                    if (currentSessionId != getActiveSessionId(engine)) return@launch
                    val fUri = f.uri ?: continue
                    // 注意: 解決不能の無言continueが再発の温床。必ずログに残すこと。
                    val doc = withContext(Dispatchers.IO) { resolveDocument(fUri) }
                    if (doc == null) {
                        android.util.Log.w("TranslationQueue", "unresolvable folder, skipping visibly: $fUri")
                        continue
                    }
                    if (doc.isDirectory) {
                        val children = withContext(Dispatchers.IO) { doc.listFiles().toList() }
                        val rawFiles = children.filter {
                            it.isFile && it.name?.endsWith(".txt", ignoreCase = true) == true &&
                                    it.name?.startsWith("part_", ignoreCase = true) != true
                        }.sortedBy { it.name }

                        if (rawFiles.isNotEmpty()) {
                            foundRawText = true
                            for (rawFile in rawFiles) {
                                val novelName = rawFile.name?.replace(Regex("""\.[tT][xX][tT]$"""), "") ?: "小説"
                                newFolderList.add(
                                    FolderItem(
                                        path = doc.uri.toString(), // 親フォルダのURI (分割済み出力先用)
                                        name = novelName,
                                        uri = rawFile.uri // 生テキストファイルのURI
                                    )
                                )
                            }
                        } else {
                            newFolderList.add(f)
                        }
                    } else {
                        newFolderList.add(f)
                    }
                }

                if (currentSessionId != getActiveSessionId(engine)) return@launch

                if (foundRawText && newFolderList.isNotEmpty()) {
                    mutate(engine) {
                        it.copy(
                            selectedFolders = newFolderList,
                            folderUri = newFolderList.first().uri,
                            folderName = displayName(newFolderList)
                        )
                    }
                    notifyChanged()
                }
            }

            if (currentSessionId != getActiveSessionId(engine)) return@launch
            startNextFolderInQueue(engine, 0, currentSessionId)
        }
    }

    /**
     * 文書解決。フォルダ系の子URIは共有のルート辿りに寄せる。
     * 注意: 子URIをfromTreeUriに直渡しするとルートに化けて誤列挙になるため禁止
     * (詳細はTranslationFileStore.resolveTreeFolder契約。同じバグの再発防止)。
     */
    private fun resolveDocument(uri: Uri): DocumentFile? {
        return if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            if (file.exists()) DocumentFile.fromFile(file) else null
        } else if (splitTreeChildUri(uri.toString()) != null) {
            // ファイル単体はfromSingleUri側に任せるため、ここではフォルダ解決を試み、駄目なら従来退行する。
            resolveTreeFolder(appContext, uri) ?: DocumentFile.fromSingleUri(appContext, uri)
        } else {
            DocumentFile.fromTreeUri(appContext, uri) ?: DocumentFile.fromSingleUri(appContext, uri)
        }
    }

    private fun getActiveSessionId(engine: TranslationEngine): Long = synchronized(taskLock) {
        when (engine) {
            TranslationEngine.GOOGLE -> googleSessionId
            TranslationEngine.DEEPL -> deeplSessionId
            TranslationEngine.PAPAGO -> papagoSessionId
            TranslationEngine.LLM_API -> 0L
        }
    }

    private fun startNextFolderInQueue(engine: TranslationEngine, folderIndex: Int, sessionId: Long) {
        // Mainで実行する。WebView生成（BaseWebTranslationTask）はMainスレッド必須。
        // 阻塞SAF解決のみ内部でwithContext(IO)に分離する。
        scope.launch {
            if (sessionId != getActiveSessionId(engine)) return@launch

            val engineState = stateFor(engine)

            val folders = engineState.selectedFolders.ifEmpty {
                if (engineState.folderUri != null) {
                    listOf(FolderItem(path = engineState.folderUri.path ?: "", name = engineState.folderName, uri = engineState.folderUri))
                } else emptyList()
            }

            if (folders.isEmpty() || folderIndex >= folders.size) {
                // 技術的根拠1行：空キューを完了表示すると未翻訳のまま成功に見えるため、対象なしは明示する。
                val emptyMsg = if (folders.isEmpty()) "翻訳対象のフォルダがありません"
                else "全 ${folders.size} フォルダの翻訳が完了しました"
                mutate(engine) {
                    it.copy(isTranslating = false, statusText = emptyMsg)
                }
                val engineName = engineDisplayName(engine)
                onShowMessage?.invoke("[$engineName 翻訳] $emptyMsg", true)
                notifyChanged()
                return@launch
            }

            val currentItem = folders[folderIndex]
            var targetUri = currentItem.uri ?: Uri.fromFile(File(currentItem.path))
            val folderProgressPrefix = if (folders.size > 1) "[フォルダ ${folderIndex + 1}/${folders.size}] " else ""

            // オンデマンド物理分割: 対象が生テキストファイルの場合、翻訳直前にこの1ファイルのみを都度分割
            // SAF解決（阻塞IPC）はIOに分離する。分割本体（splitSingleTextFile）は内部でIO化済みのmain-safe関数のためMainから呼ぶ。
            val currentDoc = currentItem.uri?.let { withContext(Dispatchers.IO) { resolveDocument(it) } }
            if (isWebSplitEnabled.value && currentDoc != null && currentDoc.isFile &&
                currentDoc.name?.endsWith(".txt", ignoreCase = true) == true &&
                currentDoc.name?.startsWith("part_", ignoreCase = true) != true
            ) {
                val parentUri = runCatching { Uri.parse(currentItem.path) }.getOrNull()
                val parentDoc = withContext(Dispatchers.IO) {
                    parentUri?.let { resolveDocument(it) } ?: currentDoc.parentFile
                }
                val splitRootDir = withContext(Dispatchers.IO) {
                    parentDoc?.let { p ->
                        p.findFile("分割済み") ?: p.createDirectory("分割済み")
                    }
                }

                if (splitRootDir != null) {
                    mutate(engine) {
                        it.copy(
                            currentFolderIndex = folderIndex,
                            folderName = if (folders.size == 1) currentItem.name else "${currentItem.name} (${folderIndex + 1}/${folders.size})",
                            isTranslating = true,
                            statusText = "${folderProgressPrefix}${currentItem.name} を分割中..."
                        )
                    }
                    notifyChanged()

                    val splitFolder = NovelPhysicalSplitter.splitSingleTextFile(
                        context = appContext,
                        fileDoc = currentDoc,
                        splitRootDir = splitRootDir,
                        splitSizeChars = webSplitSizeChars.value,
                        declared = inputEncoding.value,
                        onLog = { msg -> onShowMessage?.invoke(msg, false) }
                    )

                    if (sessionId != getActiveSessionId(engine)) return@launch

                    if (splitFolder != null) {
                        targetUri = splitFolder.uri
                    } else {
                        onShowMessage?.invoke("❌ 分割に失敗しました: ${currentItem.name}", false)
                        if (folderIndex + 1 < folders.size) {
                            startNextFolderInQueue(engine, folderIndex + 1, sessionId)
                        }
                        return@launch
                    }
                }
            }

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
                if (sessionId != getActiveSessionId(engine)) return
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
                    if (sessionId == getActiveSessionId(engine)) {
                        when (engine) {
                            TranslationEngine.GOOGLE -> googleTask = null
                            TranslationEngine.DEEPL -> deeplTask = null
                            TranslationEngine.PAPAGO -> papagoTask = null
                            TranslationEngine.LLM_API -> {}
                        }
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
                    val engineName = engineDisplayName(engine)
                    onShowMessage?.invoke("[$engineName 翻訳] $message", true)
                    notifyChanged()
                }
            }
        }

        val strategy: WebTranslationStrategy = when (engine) {
            TranslationEngine.GOOGLE -> GoogleTranslationStrategy()
            TranslationEngine.DEEPL -> DeeplTranslationStrategy()
            TranslationEngine.PAPAGO -> PapagoTranslationStrategy()
            TranslationEngine.LLM_API -> GoogleTranslationStrategy() // WebTask用フォールバック
        }

        // WebView生成はMainスレッド必須のため、ここはMain文脈のまま構築する。
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
            if (sessionId != getActiveSessionId(engine)) return@launch
            when (engine) {
                TranslationEngine.GOOGLE -> {
                    googleTask?.stop()
                    googleTask = task
                }
                TranslationEngine.DEEPL -> {
                    deeplTask?.stop()
                    deeplTask = task
                }
                TranslationEngine.PAPAGO -> {
                    papagoTask?.stop()
                    papagoTask = task
                }
                TranslationEngine.LLM_API -> {}
            }
        }
        task.start()
        }
    }

    fun stop(engine: TranslationEngine) {
        if (engine == TranslationEngine.LLM_API) {
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
                TranslationEngine.PAPAGO -> {
                    papagoSessionId++
                    val task = papagoTask
                    papagoTask = null
                    task?.stop()
                    _papagoState.update {
                        it.copy(isTranslating = false, statusText = "Papago翻訳を停止しました")
                    }
                }
                TranslationEngine.LLM_API -> {}
            }
        }
        notifyChanged()
    }

    fun shutdown() {
        synchronized(taskLock) {
            googleSessionId++
            deeplSessionId++
            papagoSessionId++

            val gTask = googleTask
            googleTask = null
            gTask?.stop()

            val dTask = deeplTask
            deeplTask = null
            dTask?.stop()

            val pTask = papagoTask
            papagoTask = null
            pTask?.stop()
        }
    }

    // ---- 内部ユーティリティ ----

    private fun engineDisplayName(engine: TranslationEngine): String = when (engine) {
        TranslationEngine.GOOGLE -> "Google"
        TranslationEngine.DEEPL -> "DeepL"
        TranslationEngine.PAPAGO -> "Papago"
        TranslationEngine.LLM_API -> "AI/LLM"
    }

    private fun stateFor(engine: TranslationEngine): EngineTranslationState =
        when (engine) {
            TranslationEngine.GOOGLE -> _googleState.value
            TranslationEngine.DEEPL -> _deeplState.value
            TranslationEngine.PAPAGO -> _papagoState.value
            TranslationEngine.LLM_API -> _llmState.value
        }

    private fun mutate(engine: TranslationEngine, f: (EngineTranslationState) -> EngineTranslationState) {
        when (engine) {
            TranslationEngine.GOOGLE -> _googleState.update(f)
            TranslationEngine.DEEPL -> _deeplState.update(f)
            TranslationEngine.PAPAGO -> _papagoState.update(f)
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