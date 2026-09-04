package com.example.novelscraper.translation.llm.engine

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.api.LlmRequestRunner
import com.example.novelscraper.translation.llm.api.LlmRetryPolicy
import com.example.novelscraper.translation.common.NovelPhysicalSplitter
import com.example.novelscraper.translation.llm.pipeline.*
import com.example.novelscraper.translation.llm.prompt.PromptBuilder
import com.example.novelscraper.translation.llm.rotation.ApiKeyPoolManager
import com.example.novelscraper.translation.llm.rotation.KeyClaimResult
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

data class LlmEngineState(
    val isTranslating: Boolean = false,
    val statusText: String = "待機中",
    val currentFolderName: String = "",
    val currentFileName: String = "",
    val progress: Pair<Int, Int> = 0 to 0,
    val chunkProgress: Pair<Int, Int> = 0 to 0,
    val totalTokensUsed: Int = 0,
    val logs: List<String> = emptyList()
)

class LlmTranslationEngine(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val _engineState = MutableStateFlow(LlmEngineState())
    val engineState: StateFlow<LlmEngineState> = _engineState.asStateFlow()

    private var currentJob: Job? = null
    private var isStopRequested = false

    var config: LlmTranslationConfig = LlmTranslationConfig()
        private set

    fun updateConfig(newConfig: LlmTranslationConfig) {
        config = newConfig
    }

    private fun addLog(message: String) {
        _engineState.update { current ->
            current.copy(logs = (current.logs + message).takeLast(200))
        }
    }

    fun startTranslation(folderUris: List<Uri>, onCompleted: () -> Unit = {}) {
        if (_engineState.value.isTranslating) return
        isStopRequested = false

        currentJob = coroutineScope.launch(Dispatchers.IO) {
            _engineState.update { it.copy(
                isTranslating = true,
                statusText = "翻訳開始準備中...",
                chunkProgress = 0 to 0,
                logs = emptyList()
            ) }
            val workerCount = config.parallelWorkers.coerceIn(1, 6)
            addLog("🚀 LLM 翻訳エンジン起動 (並列ワーカー数: ${workerCount} / 登録モデル: ${config.modelProfiles.size}件)")

            val keyPoolManager = ApiKeyPoolManager(config.geminiApiKeys)

            try {
                for ((folderIndex, folderUri) in folderUris.withIndex()) {
                    if (isStopRequested || !isActive) break

                    val docFolder = DocumentFile.fromTreeUri(context, folderUri) ?: continue
                    val folderName = docFolder.name ?: "Unknown"

                    val outSubDirName = config.outputSubDir.ifBlank { "翻訳完了_LLM" }
                    _engineState.update { it.copy(
                        currentFolderName = folderName,
                        statusText = "フォルダ処理中: $folderName (${folderIndex + 1}/${folderUris.size})"
                    ) }
                    addLog("----------------------------------------")
                    addLog("📂 フォルダ開始: $folderName")

                    // 物理分割有効時: フォルダ直下に生テキストファイルがあれば1ファイルずつ翻訳直前にオンデマンド分割
                    if (config.enableTextSplit) {
                        val rawFiles = docFolder.listFiles().filter {
                            it.isFile && it.name?.endsWith(".txt", ignoreCase = true) == true &&
                                    it.name?.startsWith("part_", ignoreCase = true) != true
                        }.sortedBy { it.name }

                        if (rawFiles.isNotEmpty()) {
                            // 親フォルダ直下で言語判定を先行実施（全分割サブフォルダで1つの判定結果を共有しキャッシュを1つに集約）
                            val parentSourceLang = detectOrLoadLanguage(docFolder, rawFiles)
                            val splitRootDir = docFolder.findFile("分割済み") ?: docFolder.createDirectory("分割済み")
                            if (splitRootDir != null) {
                                for ((rawIndex, rawFile) in rawFiles.withIndex()) {
                                    if (isStopRequested || !isActive) break
                                    val novelName = rawFile.name?.replace(Regex("""\.[tT][xX][tT]$"""), "") ?: "小説"
                                    addLog("✂️ [小説 ${rawIndex + 1}/${rawFiles.size}] 翻訳直前分割中: ${rawFile.name}")
                                    val splitSubFolder = NovelPhysicalSplitter.splitSingleTextFile(
                                        context = context,
                                        fileDoc = rawFile,
                                        splitRootDir = splitRootDir,
                                        splitSizeChars = config.textSplitSizeChars,
                                        onLog = { addLog(it) }
                                    )
                                    if (splitSubFolder != null && !isStopRequested && isActive) {
                                        addLog("📂 [小説 ${rawIndex + 1}/${rawFiles.size}] 分割済みサブフォルダ翻訳開始: ${splitSubFolder.name}")
                                        processFolder(splitSubFolder, keyPoolManager, parentSourceLang)
                                    }
                                }
                                continue // 生テキストの処理が完了したため、親フォルダ直下の直接翻訳はスキップ
                            }
                        }
                    }

                    processFolder(docFolder, keyPoolManager)
                }

                if (!isStopRequested && isActive) {
                    addLog("🛑 全フォルダの処理が完了しました")
                }
            } catch (e: CancellationException) {
                addLog("🛑 ユーザーによる停止")
            } catch (e: Exception) {
                addLog("❌ 予期せぬエラー: ${e.message}")
            } finally {
                keyPoolManager.reset()
                _engineState.update { it.copy(
                    isTranslating = false,
                    statusText = "停止中 / 完了",
                    currentFileName = ""
                ) }
                onCompleted()
            }
        }
    }

    fun stopTranslation() {
        isStopRequested = true
        currentJob?.cancel()
        _engineState.update { it.copy(
            isTranslating = false,
            statusText = "停止中..."
        ) }
    }

    /**
     * フォルダの言語を判定または既存キャッシュ (.lang_cache / .lang_cache.txt) から取得する。
     * キャッシュ欠落・空ファイル時は検出後に補完作成する。
     */
    private fun ensureLangCache(outputDir: DocumentFile?, lang: SourceLanguage): Boolean {
        if (outputDir == null) {
            addLog("❌ 言語キャッシュ保存先なし (出力フォルダ作成失敗のため未作成)")
            return false
        }
        val existing = outputDir.findFile(".lang_cache")
            ?: outputDir.findFile(".lang_cache.txt")
        if (existing != null) {
            val current = readFileContent(existing)?.trim() ?: ""
            if (current == "ZH" || current == "KO" || current == "EN" || current == "JA") {
                return true
            }
            // 空・破損 (0バイト含む) は上書きで補完する
            if (saveFileContent(existing, lang.name)) {
                addLog("🔤 言語キャッシュ補完: ${lang.displayName}")
                return true
            }
            addLog("⚠️ 言語キャッシュの上書き保存に失敗 (次回再補完します)")
            return false
        }
        val created = outputDir.createFile("text/plain", ".lang_cache")
        if (created == null) {
            addLog("❌ 言語キャッシュの作成に失敗 (SAF createFileがnullを返却)")
            return false
        }
        if (!saveFileContent(created, lang.name)) {
            addLog("⚠️ 言語キャッシュの保存に失敗 (次回再検出します)")
            return false
        }
        addLog("🔤 言語キャッシュ作成: ${lang.displayName}")
        return true
    }

    private fun detectOrLoadLanguage(
        folderDoc: DocumentFile,
        sampleFiles: List<DocumentFile>
    ): SourceLanguage {
        val outSubDirName = config.outputSubDir.ifBlank { "翻訳完了_LLM" }
        val outputDir = folderDoc.findFile(outSubDirName) ?: folderDoc.createDirectory(outSubDirName)

        val existingCache = outputDir?.findFile(".lang_cache") ?: outputDir?.findFile(".lang_cache.txt")
        if (existingCache != null) {
            val cachedCode = readFileContent(existingCache)?.trim() ?: ""
            val lang = when (cachedCode) {
                "ZH" -> SourceLanguage.ZH
                "KO" -> SourceLanguage.KO
                "EN" -> SourceLanguage.EN
                "JA" -> SourceLanguage.JA
                else -> null
            }
            if (lang != null) {
                addLog("🔤 言語 (キャッシュ読込): ${lang.displayName}")
                return lang
            }
            addLog("⚠️ 言語キャッシュが空/不正のため再検出します")
        }

        val firstFile = sampleFiles.firstOrNull()
        val firstContent = if (firstFile != null) readFileContent(firstFile) ?: "" else ""
        val langResult = LanguageDetector.detect(firstContent)
        val detected = langResult.language
        addLog("🔤 言語検出: ${detected.displayName} [${langResult.reason}]")

        ensureLangCache(outputDir, detected)
        return detected
    }

    private suspend fun processFolder(
        folderDoc: DocumentFile,
        keyPoolManager: ApiKeyPoolManager,
        inheritedSourceLang: SourceLanguage? = null
    ) {
        val outSubDirName = config.outputSubDir.ifBlank { "翻訳完了_LLM" }
        val folderName = folderDoc.name ?: "Unknown"
        val folderKey = folderDoc.uri.toString()

        // 予約フォルダ自身の再翻訳をガード
        if (folderName == outSubDirName || folderName == "分割済み" || folderName == "翻訳完了") {
            addLog("⏭ 予約フォルダ ($folderName) のためスキップ")
            return
        }

        // 物理分割有効時、親フォルダ直下に「分割済み」が存在する場合は元ファイルの直接翻訳をスキップ
        if (config.enableTextSplit) {
            val splitDir = folderDoc.findFile("分割済み")
            if (splitDir != null && splitDir.isDirectory) {
                addLog("✂️ 物理分割が有効なため、元ファイルの直接翻訳をスキップし「分割済み」配下のパートファイルを翻訳します")
                return
            }
        }

        val files = folderDoc.listFiles()
            .filter { it.isFile && it.name?.endsWith(".txt") == true && !it.name!!.endsWith(".failed") }
            .sortedBy { it.name }

        if (files.isEmpty()) {
            addLog("⚠️ 対象の .txt ファイルがありません")
            return
        }

        // 出力先サブフォルダの作成
        val outputDir = folderDoc.findFile(outSubDirName) ?: folderDoc.createDirectory(outSubDirName)
        if (outputDir == null) {
            addLog("❌ 出力先フォルダ作成失敗: $outSubDirName")
            return
        }

        // SAF O(1) 高速化: 出力ディレクトリを1回の listFiles で走査し、名前とサイズをキャッシュ。
        // サイズ0の .txt は破損とみなしてキャッシュに入れない (未翻訳扱いで次回再処理)。
        val existingOutputNames = Collections.synchronizedSet(mutableSetOf<String>())
        var zeroByteCount = 0
        for (outDoc in outputDir.listFiles()) {
            val outName = outDoc.name ?: continue
            if (outDoc.isFile && outName.endsWith(".txt") && outDoc.length() == 0L) {
                zeroByteCount++
                continue
            }
            existingOutputNames.add(outName)
        }
        if (zeroByteCount > 0) {
            addLog("⚠️ サイズ0の出力 $zeroByteCount 件は未翻訳扱いで再処理します")
        }

        // 言語判定 (継承時は再検出せず再利用するが、キャッシュ欠落時は補完作成する)
        val sourceLang: SourceLanguage
        if (inheritedSourceLang != null) {
            sourceLang = inheritedSourceLang
            ensureLangCache(outputDir, inheritedSourceLang)
        } else {
            sourceLang = detectOrLoadLanguage(folderDoc, files)
        }

        val primaryProfile = config.modelProfiles.firstOrNull() ?: ModelProfile(modelName = "gemini-3.5-flash")
        val splitThreshold = config.getEffectiveSplitThreshold(sourceLang, primaryProfile)
        val outputManChars = String.format(java.util.Locale.US, "%.1f", primaryProfile.maxOutputChars / 10000.0).removeSuffix(".0")
        val sizeLog = " (目標出力: ${outputManChars}万字 ➔ 入力閾値: ${splitThreshold / 1000}KB)"
        val modelPromptSummaries = config.modelProfiles.mapIndexed { idx, prof ->
            val pOrder = config.getEffectivePromptOrder(sourceLang, prof)
            val tag = if (prof.useCustomPromptOrder) "個別" else "共通"
            "#${idx + 1}(${prof.modelName}): $pOrder[$tag]"
        }.ifEmpty {
            listOf("#1(${primaryProfile.modelName}): ${config.getEffectivePromptOrder(sourceLang, primaryProfile)}[デフォルト]")
        }
        val promptOrderLog = " (プロンプト構成: ${modelPromptSummaries.joinToString(" / ")})"
        addLog("⚙️ 翻訳パラメータ: ${sourceLang.displayName}$sizeLog$promptOrderLog")

        // 人名辞書生成 (有効時: 動的プロバイダー & 並列バッチ)
        var novelDict: NovelDictionary? = null
        if (config.enableDictGen) {
            val existingDictFile = folderDoc.findFile("dictionary.json")
            if (existingDictFile != null) {
                val dictJson = readFileContent(existingDictFile) ?: ""
                novelDict = NovelDictionaryGenerator.parseDictionaryJson(dictJson)
                if (novelDict != null) {
                    addLog("📖 既存の辞書をロード: ${novelDict.characters.size}名 (スタイル:${novelDict.style})")
                }
            }

            if (novelDict == null) {
                val dictProvider = config.dictProvider
                val dictModel = config.getEffectiveDictModel(dictProvider)
                val mergeModel = config.getEffectiveDictMergeModel(dictProvider)
                val providerOrder = config.getEffectiveDictProviderOrder()
                val providerAllowFallbacks = config.getEffectiveDictProviderAllowFallbacks()

                val dictApiKeys = when (dictProvider) {
                    LlmProvider.GEMINI -> config.geminiApiKeys.filter { it.isNotBlank() }.ifEmpty { listOf("") }
                    LlmProvider.OPENROUTER -> listOf(config.openRouterApiKey)
                    LlmProvider.GROQ -> listOf(config.groqApiKey)
                }
                val dictEndpoint = when (dictProvider) {
                    LlmProvider.GEMINI -> ""
                    LlmProvider.OPENROUTER -> config.openRouterEndpoint
                    LlmProvider.GROQ -> config.groqEndpoint
                }

                novelDict = NovelDictionaryGenerator.generate(
                    context = context,
                    folderDoc = folderDoc,
                    sampleFiles = files,
                    provider = dictProvider,
                    apiKeys = dictApiKeys,
                    model = dictModel,
                    mergeModel = mergeModel,
                    endpoint = dictEndpoint,
                    providerOrder = providerOrder,
                    providerAllowFallbacks = providerAllowFallbacks,
                    maxBatchBytes = config.dictBatchMaxBytes,
                    maxTotalParts = config.dictTotalParts,
                    sampleMode = config.dictSampleMode,
                    maxTotalScanBytes = config.dictMaxTotalScanBytes,
                    parallelCount = config.getEffectiveDictParallelCount(),
                    requestDelaySec = config.dictRequestDelaySec,
                    cooldown429Sec = config.dict429CooldownSec,
                    onLog = { addLog(it) }
                )
            }

            if (novelDict == null) {
                addLog("⛔ ${folderDoc.name} : 辞書未完成のため翻訳をスキップ (次回再挑戦)")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "【翻訳スキップ】API制限等により人名辞書の生成に失敗しました: ${folderDoc.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }

        val totalCount = files.size
        val completedCounter = AtomicInteger(0)
        val folderProcessedCounter = AtomicInteger(0)

        // 翻訳済みファイルの事前カウント (O(1) メモリ照合)。
        // 作業ディレクトリ (.parts_) が残る大ファイルはレジューム優先で未完了扱い。
        for (f in files) {
            val fname = f.name ?: continue
            val hasWorkDir = existingOutputNames.contains(".parts_${fname}")
            if (!hasWorkDir && (existingOutputNames.contains(fname) || existingOutputNames.contains("$fname.failed"))) {
                completedCounter.incrementAndGet()
            }
        }

        _engineState.update { it.copy(
            progress = completedCounter.get() to totalCount
        ) }

        val requestedWorkerCount = config.parallelWorkers.coerceIn(1, 6)
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }

        // 排他制御はジョブ単位で生成 (停止→即再開のレースを根絶)
        val fileClaimManager = FileClaimManager()

        // 各ワーカーを起動 (1ワーカー1キー専有)
        coroutineScope {
                val workerJobs = mutableListOf<Deferred<Unit>>()

                for (wId in 1..requestedWorkerCount) {
                    val keyClaim = if (config.geminiRotationEnabled) {
                        keyPoolManager.claimNewKey()
                    } else {
                        (0 to (config.geminiApiKeys.firstOrNull() ?: ""))
                    }

                    if (keyClaim == null) {
                        addLog("⚠️ [W#$wId] 専有できる未使用 Gemini API キーがないため起動をスキップします")
                        continue
                    }

                    val (claimedKeyIdx, claimedKey) = keyClaim
                    addLog("🚀 [W#$wId] ワーカー起動 (専有キー[${claimedKeyIdx + 1}/${config.geminiApiKeys.size}])")

                    val rotationManager = LlmRotationManager(
                        workerId = wId,
                        currentKeyIndex = claimedKeyIdx,
                        currentKey = claimedKey,
                        profiles = profiles,
                        keyPoolManager = if (config.geminiRotationEnabled) keyPoolManager else null,
                        switchCooldownSec = config.geminiCooldownSec
                    )

                    val job = async(Dispatchers.IO) {
                        if (wId > 1) {
                            val delaySec = (wId - 1) * 10
                            addLog("⏳ [W#$wId] 503過負荷回避のため ${delaySec}秒待機後に開始します...")
                            for (s in 0 until delaySec) {
                                if (isStopRequested || !currentCoroutineContext().isActive) return@async
                                delay(1000L)
                            }
                        }
                        try {
                            runWorker(
                                workerId = wId,
                                folderKey = folderKey,
                                fileClaimManager = fileClaimManager,
                                allFiles = files,
                                outputDir = outputDir,
                                existingOutputNames = existingOutputNames,
                                sourceLang = sourceLang,
                                novelDict = novelDict,
                                completedCounter = completedCounter,
                                folderProcessedCounter = folderProcessedCounter,
                                totalCount = totalCount,
                                rotationManager = rotationManager
                            )
                        } finally {
                            rotationManager.release()
                        }
                    }
                    workerJobs.add(job)
                }

                workerJobs.awaitAll()
            }
    }

    private suspend fun runWorker(
        workerId: Int,
        folderKey: String,
        fileClaimManager: FileClaimManager,
        allFiles: List<DocumentFile>,
        outputDir: DocumentFile,
        existingOutputNames: MutableSet<String>,
        sourceLang: SourceLanguage,
        novelDict: NovelDictionary?,
        completedCounter: AtomicInteger,
        folderProcessedCounter: AtomicInteger,
        totalCount: Int,
        rotationManager: LlmRotationManager
    ) {
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }

        // 直前原文末尾のスライディングキャッシュ (1件分のみ保持)
        var cachedTailName: String? = null
        var cachedTailText: String? = null

        // DocumentFile の線形 indexOf を避けるための URI→位置マップ
        val fileIndexByUri = allFiles.mapIndexed { index, doc -> doc.uri.toString() to index }.toMap()

        for (fileDoc in allFiles) {
            if (isStopRequested || !currentCoroutineContext().isActive || rotationManager.isExhausted) break

            // フォルダ処理上限チェック
            if (config.filesPerFolder > 0 && folderProcessedCounter.get() >= config.filesPerFolder) {
                break
            }

            val fileName = fileDoc.name ?: continue

            // 作業ディレクトリ (.parts_) が残る大ファイルはレジューム優先でスキップしない
            val hasResumeWork = existingOutputNames.contains(".parts_${fileName}")

            // 翻訳済みまたは失敗保持ならスキップ (O(1) キャッシュ照合)
            if (!hasResumeWork && (existingOutputNames.contains(fileName) || existingOutputNames.contains("$fileName.failed"))) {
                continue
            }

            // 排他クレーム試行 (他ワーカーが着手中ならスキップ)
            if (!fileClaimManager.tryClaimFile(folderKey, fileName)) {
                continue
            }

            val rawContent = readFileContent(fileDoc, fileName)
            if (rawContent == null) {
                addLog("[W#$workerId] ⚠️ $fileName 読み取り失敗 → クレーム解放して次へ")
                fileClaimManager.releaseFile(folderKey, fileName)
                continue
            }
            val content = TextCleanser.cleanse(rawContent)

            // 空白・0バイトファイルの即時スキップ
            if (content.isBlank()) {
                val outFile = outputDir.findFile(fileName) ?: outputDir.createFile("text/plain", fileName)
                if (outFile != null) {
                    if (saveFileContent(outFile, "")) {
                        existingOutputNames.add(fileName)
                    } else {
                        addLog("[W#$workerId] ❌ $fileName 空ファイル出力の保存に失敗")
                    }
                }
                fileClaimManager.releaseFile(folderKey, fileName)
                addLog("[W#$workerId] ⏭ $fileName (空ファイルのためスキップ)")
                val done = completedCounter.incrementAndGet()
                folderProcessedCounter.incrementAndGet()
                _engineState.update { it.copy(
                    progress = done to totalCount,
                    currentFileName = fileName
                ) }
                continue
            }

            val fsize = content.toByteArray(Charsets.UTF_8).size

            // 直前ファイルの原文末尾コンテキスト (直前1件分のみスライディング保持。再読み込みなし)
            val prevSourceTail = if (config.enablePrevSrcContext) {
                val fileIdx = fileIndexByUri[fileDoc.uri.toString()] ?: -1
                if (fileIdx > 0) {
                    val prevFile = allFiles[fileIdx - 1]
                    val prevName = prevFile.name ?: ""
                    if (cachedTailName == prevName) {
                        cachedTailText
                    } else {
                        val prevRaw = readFileContent(prevFile, prevName)
                        val tail = prevRaw?.let {
                            TextCleanser.cleanse(it).lines().takeLast(config.prevSrcContextLines).joinToString("\n")
                        }
                        cachedTailName = prevName
                        cachedTailText = tail
                        tail
                    }
                } else null
            } else null

            val workDir = outputDir.findFile(".parts_${fileName}")
            val activeProfile = rotationManager.getCurrentProfile()
            val effectiveSplitThreshold = config.getEffectiveSplitThreshold(sourceLang, activeProfile)
            val effectiveBatchSize = config.getEffectiveBatchSize(sourceLang, activeProfile)

            if (workDir != null || fsize > effectiveSplitThreshold) {
                try {
                    // 大ファイル: 分割チャンク翻訳 (レジューム対応)。
                    // 親直下に .failed を作らない (.parts_ + chunk_N.failed が中断シグナル)。
                    addLog("[W#$workerId] $fileName (大ファイル: ${fsize}B [閾値:${effectiveSplitThreshold}B] / 専有キー[${rotationManager.getCurrentKeyIndex() + 1}])")
                    val success = LargeFileTranslator.translateLargeFile(
                        context = context,
                        fileName = fileName,
                        fileContent = content,
                        outputDir = outputDir,
                        config = config,
                        rotationManager = rotationManager,
                        sourceLang = sourceLang,
                        dictMap = novelDict?.characters,
                        dictStyle = novelDict?.style,
                        dictGenders = novelDict?.genders,
                        prevSourceTail = prevSourceTail,
                        isStopRequested = { isStopRequested },
                        onLog = { addLog("[W#$workerId] $it") },
                        onChunkProgress = { done, total ->
                            _engineState.update { it.copy(chunkProgress = done to total) }
                        }
                    )

                    if (success) {
                        existingOutputNames.add(fileName)
                        existingOutputNames.remove(".parts_${fileName}")
                    } else {
                        if (!isStopRequested && currentCoroutineContext().isActive) {
                            addLog("[W#$workerId] ❌ $fileName 分割翻訳未完了 → .parts_残存のため次回レジューム")
                        }
                    }

                    val done = completedCounter.incrementAndGet()
                    folderProcessedCounter.incrementAndGet()
                    _engineState.update { it.copy(
                        progress = done to totalCount,
                        currentFileName = fileName,
                        chunkProgress = 0 to 0
                    ) }
                } finally {
                    fileClaimManager.releaseFile(folderKey, fileName)
                }
            } else {
                val batchItems = mutableListOf<Pair<DocumentFile, String>>()
                batchItems.add(fileDoc to content)
                var currentBatchBytes = fsize

                try {
                    val currentIdx = fileIndexByUri[fileDoc.uri.toString()] ?: -1
                    if (currentIdx >= 0) {
                        for (nextIdx in (currentIdx + 1) until allFiles.size) {
                            if (batchItems.size >= 3) break // 1バッチ最大3ファイル (セグメント不一致抑制)
                            val nextDoc = allFiles[nextIdx]
                            val nextName = nextDoc.name ?: continue
                            if (existingOutputNames.contains(nextName) || existingOutputNames.contains("$nextName.failed")) continue
                            val nextWorkDir = outputDir.findFile(".parts_${nextName}")
                            if (nextWorkDir != null) continue

                            // 排他クレーム試行
                            if (!fileClaimManager.tryClaimFile(folderKey, nextName)) continue

                            val nextRaw = readFileContent(nextDoc, nextName)
                            if (nextRaw == null) {
                                addLog("[W#$workerId] ⚠️ $nextName 読み取り失敗 → クレーム解放して次へ")
                                fileClaimManager.releaseFile(folderKey, nextName)
                                continue
                            }
                            val nextClean = TextCleanser.cleanse(nextRaw)
                            if (nextClean.isBlank()) {
                                val outF = outputDir.findFile(nextName) ?: outputDir.createFile("text/plain", nextName)
                                if (outF != null && saveFileContent(outF, "")) {
                                    existingOutputNames.add(nextName)
                                }
                                completedCounter.incrementAndGet()
                                folderProcessedCounter.incrementAndGet()
                                fileClaimManager.releaseFile(folderKey, nextName)
                                continue
                            }

                            val nextBytes = nextClean.toByteArray(Charsets.UTF_8).size
                            if (nextBytes > effectiveSplitThreshold || (currentBatchBytes + nextBytes) > effectiveBatchSize) {
                                // 大ファイルまたはバッチ上限超過: 今回のバッチには含めない (クレーム解放)
                                fileClaimManager.releaseFile(folderKey, nextName)
                                break
                            }

                            batchItems.add(nextDoc to nextClean)
                            currentBatchBytes += nextBytes
                        }
                    }

                    if (batchItems.size > 1) {
                        // 2件以上: まとめてバッチ翻訳実行（部分回収・単体フォールバックは内部で完結）
                        val batchNames = batchItems.map { it.first.name ?: "" }
                        addLog("[W#$workerId] 📦 バッチ翻訳開始 (${batchItems.size}ファイル / 計:${currentBatchBytes}B / 枠:${effectiveBatchSize}B)")
                        val outcome = translateBatchFiles(
                            batchItems = batchItems,
                            outputDir = outputDir,
                            existingOutputNames = existingOutputNames,
                            rotationManager = rotationManager,
                            sourceLang = sourceLang,
                            novelDict = novelDict,
                            prevSourceTail = prevSourceTail,
                            workerId = workerId
                        )

                        // カウンタ加算はここに一本化（translateBatchFiles内部では加算しない）
                        if (outcome.settled > 0) {
                            val done = completedCounter.addAndGet(outcome.settled)
                            folderProcessedCounter.addAndGet(outcome.settled)
                            _engineState.update { it.copy(
                                progress = done to totalCount,
                                currentFileName = batchNames.last()
                            ) }
                        }
                        if (outcome.completed < batchItems.size) {
                            addLog("[W#$workerId] ⚠️ バッチ未完 (確定:${outcome.completed}/${batchItems.size}) → 未確定分は次回再試行")
                        }
                    } else {
                        // 1件のみ: 単体翻訳
                        addLog("[W#$workerId] $fileName (単体処理: ${fsize}B / 専有キー[${rotationManager.getCurrentKeyIndex() + 1}])")
                        val success = translateSingleFile(
                            fileDoc = fileDoc,
                            content = content,
                            outputDir = outputDir,
                            existingOutputNames = existingOutputNames,
                            rotationManager = rotationManager,
                            sourceLang = sourceLang,
                            novelDict = novelDict,
                            prevSourceTail = prevSourceTail,
                            workerId = workerId
                        )
                        if (success) {
                            existingOutputNames.add(fileName)
                        }
                        val done = completedCounter.incrementAndGet()
                        folderProcessedCounter.incrementAndGet()
                        _engineState.update { it.copy(
                            progress = done to totalCount,
                            currentFileName = fileName
                        ) }
                    }
                } finally {
                    // バッチ採用分のクレームを確実に解放 (例外・キャンセル時も安全)
                    for (item in batchItems) {
                        fileClaimManager.releaseFile(folderKey, item.first.name ?: "")
                    }
                }
            }
        }
    }

    /**
     * 複数小ファイルのバッチ翻訳 ([SEG:N] オーケストレーション)
     */
    private suspend fun translateBatchFiles(
        batchItems: List<Pair<DocumentFile, String>>,
        outputDir: DocumentFile,
        existingOutputNames: MutableSet<String>,
        rotationManager: LlmRotationManager,
        sourceLang: SourceLanguage,
        novelDict: NovelDictionary?,
        prevSourceTail: String?,
        workerId: Int
    ): BatchOutcome {
        val filePairs = batchItems.map { (doc, text) -> (doc.name ?: "file.txt") to text }
        val combinedInput = BatchTranslator.buildBatchInput(filePairs)
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }
        var parsedSegments: Map<Int, String>? = null

        // --- 1. ドライバーループ ---
        for ((drvIdx, profile) in profiles.withIndex()) {
            if (isStopRequested || !currentCoroutineContext().isActive) break

            if (drvIdx > 0) {
                val prev = profiles[drvIdx - 1]
                addLog("[W#$workerId] 🔄 バッチ: ${prev.modelName} 全失敗 → ${profile.modelName} (${profile.provider.name}) へフォールバック")
            }

            val promptList = config.getEffectivePromptOrder(sourceLang, profile).ifEmpty { listOf(1, 1) }
            // JSON SchemaはGeminiかつ両フラグONのプロファイルでのみ試行し、失敗時はXMLへ自動劣化する
            val jsonEligible = config.enableBatchJsonSchema && profile.useJsonSchema &&
                profile.provider == LlmProvider.GEMINI

            // --- 2. プロンプトループ ---
            for (promptNum in promptList) {
                if (isStopRequested || !currentCoroutineContext().isActive) break

                val xmlPrompt = PromptBuilder.buildBatchPrompt(
                    promptNumber = promptNum,
                    fileCount = batchItems.size,
                    customPrompts = config.customPrompts,
                    previousSourceTail = if (config.enablePrevSrcContext) prevSourceTail else null,
                    sourceText = combinedInput,
                    dictionaryStyle = novelDict?.style,
                    dictionaryMap = novelDict?.characters,
                    dictionaryGenders = novelDict?.genders,
                    jsonMode = false
                )
                val jsonPrompt = if (jsonEligible) {
                    PromptBuilder.buildBatchPrompt(
                        promptNumber = promptNum,
                        fileCount = batchItems.size,
                        customPrompts = config.customPrompts,
                        previousSourceTail = if (config.enablePrevSrcContext) prevSourceTail else null,
                        sourceText = combinedInput,
                        dictionaryStyle = novelDict?.style,
                        dictionaryMap = novelDict?.characters,
                        dictionaryGenders = novelDict?.genders,
                        jsonMode = true
                    )
                } else null
                var jsonAttempted = false

                // --- 3. ネットワークリトライループ ---
                var retry = 0
                val maxRetryCount = if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                    rotationManager.poolCapacity.coerceAtLeast(3)
                } else 3

                while (retry < maxRetryCount) {
                    if (isStopRequested || !currentCoroutineContext().isActive) break

                    val activeProfile = rotationManager.getCurrentProfile()
                    val targetProfile = if (profile.provider == LlmProvider.GEMINI) activeProfile else profile
                    val useJson = jsonPrompt != null && !jsonAttempted

                    val apiResult = if (useJson) {
                        LlmRequestRunner.callForProfile(
                            config,
                            rotationManager,
                            targetProfile,
                            jsonPrompt,
                            combinedInput,
                            "application/json",
                            BatchTranslator.buildBatchJsonSchema()
                        )
                    } else {
                        callApiForProfile(targetProfile, xmlPrompt, combinedInput, rotationManager)
                    }

                    when (apiResult) {
                        is LlmApiResult.Success -> {
                            if (useJson) {
                                jsonAttempted = true
                                val jsonParsed = BatchTranslator.parseJsonResponse(apiResult.text)
                                if (!jsonParsed.isNullOrEmpty()) {
                                    addLog("[W#$workerId] ✅ バッチ: JSON Schema応答を取得 (${jsonParsed.size}/${batchItems.size}件)")
                                    parsedSegments = jsonParsed
                                    break
                                }
                                addLog("[W#$workerId] ⚠️ バッチ: JSONパース失敗 → XMLへ劣化して再試行")
                                continue
                            }

                            if (!CompletionMarkerHelper.checkBatchCompletion(apiResult.text, true)) {
                                addLog("[W#$workerId] ⚠️ バッチ: 完走タグなし (生成途絶疑い) → 次のプロンプトへ")
                                break
                            }

                            val parsed = BatchTranslator.parseBatchResponse(apiResult.text, batchItems.size)
                            if (parsed.isNullOrEmpty()) {
                                addLog("[W#$workerId] ⚠️ バッチ: セグメント分離失敗 (形式不一致/欠落) → 次のプロンプトへ")
                                break
                            }

                            // 欠番があっても部分回収へ進む（ salvaged できなかった分のみ単体フォールバック）
                            if (parsed.size < batchItems.size) {
                                addLog("[W#$workerId] ⚠️ バッチ: 部分抽出 (${parsed.size}/${batchItems.size}件) → 欠落分は単体フォールバックへ")
                            }
                            parsedSegments = parsed
                            break
                        }
                        is LlmApiResult.QuotaExceeded -> {
                            if (targetProfile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                                val advanced = rotationManager.advanceRotation(apiResult.message) { addLog("[W#$workerId]  $it") }
                                if (!advanced) return BatchOutcome(0, 0)
                                retry++
                            } else {
                                addLog("[W#$workerId] ⏳ バッチ制限待機 (429): ${apiResult.message.take(100)}...")
                                delay(5000L)
                                retry++
                            }
                        }
                        is LlmApiResult.NetworkError -> {
                            if (retry + 1 >= maxRetryCount) {
                                addLog("[W#$workerId] ⚠️ バッチ通信エラー (${apiResult.statusCode}): ${apiResult.message.take(120)} ➔ 再試行上限到達")
                            } else {
                                val waitMs = LlmRetryPolicy.backoffDelayMs(retry)
                                addLog("[W#$workerId] ⚠️ バッチ通信エラー (${apiResult.statusCode}): ${apiResult.message.take(120)} ➔ ${(waitMs / 1000)}秒後再試行 [${retry + 1}/$maxRetryCount]")
                                delay(waitMs)
                            }
                            retry++
                        }
                        is LlmApiResult.QualityError -> {
                            addLog("[W#$workerId] ⚠️ バッチ品質エラー: ${apiResult.reason} ➔ 次のプロンプト/モデルへ")
                            break
                        }
                        is LlmApiResult.FatalError -> {
                            if (useJson && apiResult.statusCode == 400) {
                                jsonAttempted = true
                                addLog("[W#$workerId] ⚠️ バッチ: JSON Schema未対応 (400) → XMLへ劣化して再試行")
                                continue
                            }
                            addLog("[W#$workerId] ❌ バッチ致命的エラー (${apiResult.statusCode}): ${apiResult.message.take(200)} ➔ 次のプロンプト/モデルへ")
                            break
                        }
                    }
                }

                if (parsedSegments != null) break
            }

            if (parsedSegments != null) break
        }

        if (parsedSegments != null) {
            return salvageBatchSegments(
                parsed = parsedSegments,
                batchItems = batchItems,
                outputDir = outputDir,
                existingOutputNames = existingOutputNames,
                rotationManager = rotationManager,
                sourceLang = sourceLang,
                novelDict = novelDict,
                prevSourceTail = prevSourceTail,
                workerId = workerId
            )
        }

        addLog("[W#$workerId] ⚠️ バッチ全滅 (全ドライバー失敗) → 未完了のまま保持 (次回再試行)")
        return BatchOutcome(0, 0)
    }

    /**
     * バッチ処理の確定結果。completed=翻訳確定数、settled=処理済数（確定＋失敗確定）。
     * 中断により未処理の分はどちらにも含めない。
     */
    private data class BatchOutcome(val completed: Int, val settled: Int)

    /**
     * バッチ抽出結果の部分回収：成功分は即保存し、
     * 欠落・品質NG・保存失敗分のみ単体翻訳へフォールバックする。
     * 全件揃いを要求しないため、正常分が巻き添え破棄されない。
     */
    private suspend fun salvageBatchSegments(
        parsed: Map<Int, String>,
        batchItems: List<Pair<DocumentFile, String>>,
        outputDir: DocumentFile,
        existingOutputNames: MutableSet<String>,
        rotationManager: LlmRotationManager,
        sourceLang: SourceLanguage,
        novelDict: NovelDictionary?,
        prevSourceTail: String?,
        workerId: Int
    ): BatchOutcome {
        val (minRatio, maxRatio) = config.getSizeRatioRange(sourceLang)
        val failedItems = mutableListOf<Pair<DocumentFile, String>>()
        var completed = 0
        var settled = 0

        for ((idx, item) in batchItems.withIndex()) {
            if (isStopRequested || !currentCoroutineContext().isActive) break
            val segNum = idx + 1
            val fname = item.first.name ?: "file_$segNum.txt"
            val segText = parsed[segNum]

            if (segText.isNullOrBlank()) {
                addLog("[W#$workerId] ⚠️ バッチ[id:$segNum]: 欠落 → 単体フォールバック対象へ")
                failedItems.add(item)
                continue
            }

            val cleanedSeg = TranslationQualityValidator.stripPreamble(segText)
            val v = TranslationQualityValidator.validate(item.second, cleanedSeg, sourceLang, minRatio, maxRatio)
            if (v is QualityValidationResult.Failure) {
                addLog("[W#$workerId] ⚠️ バッチ[id:$segNum]: 品質NG (${v.reason}) → 単体フォールバック対象へ")
                failedItems.add(item)
                continue
            }

            val outFile = outputDir.findFile(fname) ?: outputDir.createFile("text/plain", fname)
            if (outFile != null && saveFileContent(outFile, cleanedSeg)) {
                existingOutputNames.add(fname)
                completed++
                settled++
                addLog("[W#$workerId] ✅ $fname (バッチ部分回収保存)")
            } else {
                addLog("[W#$workerId] ❌ $fname バッチ保存失敗 → 単体フォールバックへ")
                failedItems.add(item)
            }
        }

        // 失敗分のみ単体翻訳へ（成功分は確定済みのため再翻訳しない）
        if (failedItems.isNotEmpty()) {
            if (isStopRequested || !currentCoroutineContext().isActive) {
                addLog("[W#$workerId] 🛑 バッチ部分回収: 中断のため残り${failedItems.size}件は未処理で保持")
                return BatchOutcome(completed, settled)
            }
            addLog("[W#$workerId] ⚠️ バッチ部分回収: ${failedItems.size}件を単体翻訳へフォールバック")
            for (item in failedItems) {
                if (isStopRequested || !currentCoroutineContext().isActive) break
                if (translateSingleFile(
                        fileDoc = item.first,
                        content = item.second,
                        outputDir = outputDir,
                        existingOutputNames = existingOutputNames,
                        rotationManager = rotationManager,
                        sourceLang = sourceLang,
                        novelDict = novelDict,
                        prevSourceTail = prevSourceTail,
                        workerId = workerId
                    )
                ) {
                    completed++
                }
                settled++
            }
        }

        return BatchOutcome(completed, settled)
    }

    /**
     * 単体ファイル翻訳 (モデルローテーション完全反映 & 中断保護)
     */
    private suspend fun translateSingleFile(
        fileDoc: DocumentFile,
        content: String,
        outputDir: DocumentFile,
        existingOutputNames: MutableSet<String>,
        rotationManager: LlmRotationManager,
        sourceLang: SourceLanguage,
        novelDict: NovelDictionary?,
        prevSourceTail: String?,
        workerId: Int
    ): Boolean {
        val fileName = fileDoc.name ?: "file.txt"
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }
        var translatedText: String? = null

        // --- 1. ドライバーループ ---
        for ((drvIdx, profile) in profiles.withIndex()) {
            if (isStopRequested || !currentCoroutineContext().isActive) break

            if (drvIdx > 0) {
                val prev = profiles[drvIdx - 1]
                addLog("[W#$workerId] 🔄 $fileName: ${prev.modelName} 全失敗 → ${profile.modelName} (${profile.provider.name}) へフォールバック")
            }

            val promptList = config.getEffectivePromptOrder(sourceLang, profile).ifEmpty { listOf(1, 1) }

            // --- 2. プロンプトループ ---
            for (promptNum in promptList) {
                if (isStopRequested || !currentCoroutineContext().isActive) break

                val prompt = PromptBuilder.buildPrompt(
                    promptNumber = promptNum,
                    customPrompts = config.customPrompts,
                    previousSourceTail = if (config.enablePrevSrcContext) prevSourceTail else null,
                    sourceText = content,
                    dictionaryStyle = novelDict?.style,
                    dictionaryMap = novelDict?.characters,
                    dictionaryGenders = novelDict?.genders,
                    enableCompletionMarker = config.enableCompletionMarker
                )

                val preparedSource = CompletionMarkerHelper.appendMarker(content, config.enableCompletionMarker)

                // --- 3. ネットワークリトライループ ---
                var retry = 0
                val maxRetryCount = if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                    rotationManager.poolCapacity.coerceAtLeast(3)
                } else 3

                while (retry < maxRetryCount) {
                    if (isStopRequested || !currentCoroutineContext().isActive) break

                    val activeProfile = rotationManager.getCurrentProfile()
                    val targetProfile = if (profile.provider == LlmProvider.GEMINI) activeProfile else profile

                    val apiResult = callApiForProfile(targetProfile, prompt, preparedSource, rotationManager)

                    when (apiResult) {
                        is LlmApiResult.Success -> {
                            val markerStripped = CompletionMarkerHelper.checkAndStripMarker(apiResult.text, config.enableCompletionMarker)
                            if (markerStripped == null) {
                                addLog("[W#$workerId] ⚠️ $fileName: 完了マーカーなし → 次のプロンプトへ")
                                break
                            }
                            val cleaned = TranslationQualityValidator.stripPreamble(markerStripped)
                            val (minRatio, maxRatio) = config.getSizeRatioRange(sourceLang)
                            val validation = TranslationQualityValidator.validate(content, cleaned, sourceLang, minRatio, maxRatio)
                            if (validation is QualityValidationResult.Success) {
                                translatedText = cleaned
                                break
                            } else if (validation is QualityValidationResult.Failure) {
                                addLog("[W#$workerId] ⚠️ $fileName: 品質NG (${validation.reason}) → 次のプロンプトへ")
                                break
                            }
                        }
                        is LlmApiResult.QuotaExceeded -> {
                            if (targetProfile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                                val advanced = rotationManager.advanceRotation(apiResult.message) { addLog("[W#$workerId]  $it") }
                                if (!advanced) {
                                    return false // ワーカー終了
                                }
                                retry++
                            } else {
                                delay(5000L)
                                retry++
                            }
                        }
                        is LlmApiResult.NetworkError -> {
                            if (retry + 1 >= maxRetryCount) {
                                addLog("[W#$workerId] ⚠️ $fileName 通信エラー (${apiResult.statusCode}): ${apiResult.message.take(120)} ➔ 再試行上限到達")
                            } else {
                                val waitMs = LlmRetryPolicy.backoffDelayMs(retry)
                                addLog("[W#$workerId] ⚠️ $fileName 通信エラー (${apiResult.statusCode}): ${apiResult.message.take(120)} ➔ ${(waitMs / 1000)}秒後再試行 [${retry + 1}/$maxRetryCount]")
                                delay(waitMs)
                            }
                            retry++
                        }
                        is LlmApiResult.QualityError -> {
                            addLog("[W#$workerId] ⚠️ $fileName 品質エラー: ${apiResult.reason} ➔ 次のプロンプト/モデルへ")
                            break
                        }
                        is LlmApiResult.FatalError -> {
                            addLog("[W#$workerId] ❌ $fileName 致命的エラー (${apiResult.statusCode}): ${apiResult.message.take(200)} ➔ 次のプロンプト/モデルへ")
                            break
                        }
                    }
                }

                if (translatedText != null) break
            }

            if (translatedText != null) break
        }

        if (translatedText != null) {
            val outFile = outputDir.findFile(fileName) ?: outputDir.createFile("text/plain", fileName)
            if (outFile != null && saveFileContent(outFile, translatedText)) {
                existingOutputNames.add(fileName)
                addLog("[W#$workerId] ✅ $fileName")
                return true
            }
            addLog("[W#$workerId] ❌ $fileName 翻訳結果の保存失敗 → 未完了のまま保持")
        } else {
            // 中断された場合は .failed を作成せず次回再開可能に保持
            if (!isStopRequested && currentCoroutineContext().isActive) {
                val failedFileName = "$fileName.failed"
                val failedFile = outputDir.findFile(failedFileName) ?: outputDir.createFile("text/plain", failedFileName)
                if (failedFile != null && saveFileContent(failedFile, content)) {
                    existingOutputNames.add(failedFileName)
                    addLog("[W#$workerId] ❌ $fileName (全ドライバー全プロンプト失敗) → .failed")
                } else {
                    addLog("[W#$workerId] ❌ $fileName .failed の保存失敗 → 未完了のまま保持")
                }
            }
        }
        return false
    }

    private suspend fun callApiForProfile(
        profile: ModelProfile,
        prompt: String,
        sourceText: String,
        rotationManager: LlmRotationManager
    ): LlmApiResult {
        return LlmRequestRunner.callForProfile(config, rotationManager, profile, prompt, sourceText)
    }

    private fun readFileContent(doc: DocumentFile, fileName: String = ""): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                TextCharsetDetector.readTextAutoDetect(stream)
            }
        } catch (e: Exception) {
            addLog("⚠️ ${fileName.ifBlank { doc.name ?: "不明" }} 読み取り例外: ${e.message}")
            null
        }
    }

    private fun saveFileContent(doc: DocumentFile, content: String): Boolean {
        return try {
            val stream = context.contentResolver.openOutputStream(doc.uri, "wt") ?: return false
            stream.use { s ->
                s.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
