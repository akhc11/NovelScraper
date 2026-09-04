package com.example.novelscraper.translation.llm.engine

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.api.GeminiApiClient
import com.example.novelscraper.translation.llm.api.LlmApiClient
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.api.OpenAiCompatibleClient
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
    private val fileClaimManager = FileClaimManager()

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
                                        processFolder(splitSubFolder, keyPoolManager)
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
                fileClaimManager.clear()
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

    private suspend fun processFolder(
        folderDoc: DocumentFile,
        keyPoolManager: ApiKeyPoolManager
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

        // SAF O(1) 高速化: 出力ディレクトリの既存ファイル名を1回の listFiles でキャッシュ (フリーズ根絶)
        val existingOutputNames = Collections.synchronizedSet(
            outputDir.listFiles().mapNotNull { it.name }.toMutableSet()
        )

        // 言語判定 (キャッシュ .lang_cache を確認、無ければ先頭ファイルで判定して保存)
        val langCacheDoc = if (existingOutputNames.contains(".lang_cache")) outputDir.findFile(".lang_cache") else null
        val sourceLang: SourceLanguage = if (langCacheDoc != null) {
            val cachedCode = readFileContent(langCacheDoc)?.trim() ?: ""
            when (cachedCode) {
                "ZH" -> SourceLanguage.ZH
                "KO" -> SourceLanguage.KO
                "EN" -> SourceLanguage.EN
                "JA" -> SourceLanguage.JA
                else -> SourceLanguage.ZH
            }.also {
                addLog("🔤 言語 (キャッシュ読込): ${it.displayName}")
            }
        } else {
            val firstContent = readFileContent(files.first()) ?: ""
            val langResult = LanguageDetector.detect(firstContent)
            val detected = langResult.language
            addLog("🔤 言語検出: ${detected.displayName} [${langResult.reason}]")
            val newCacheDoc = outputDir.findFile(".lang_cache") ?: outputDir.createFile("text/plain", ".lang_cache")
            if (newCacheDoc != null) {
                saveFileContent(newCacheDoc, detected.name)
                existingOutputNames.add(".lang_cache")
            }
            detected
        }

        val primaryProfile = config.modelProfiles.firstOrNull() ?: ModelProfile(modelName = "gemini-3.5-flash")
        val splitThreshold = config.getEffectiveSplitThreshold(sourceLang, primaryProfile)
        val sizeLog = " (目標出力: ${primaryProfile.maxOutputChars / 1000}万字 ➔ 入力閾値: ${splitThreshold / 1000}KB)"
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

        // 翻訳済みファイルの事前カウント (O(1) メモリ照合)
        val pendingFiles = mutableListOf<DocumentFile>()
        for (f in files) {
            val fname = f.name ?: continue
            if (existingOutputNames.contains(fname) || existingOutputNames.contains("$fname.failed")) {
                completedCounter.incrementAndGet()
            } else {
                pendingFiles.add(f)
            }
        }

        _engineState.update { it.copy(
            progress = completedCounter.get() to totalCount
        ) }

        val requestedWorkerCount = config.parallelWorkers.coerceIn(1, 6)
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }

        try {
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
                        try {
                            runWorker(
                                workerId = wId,
                                folderKey = folderKey,
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
        } finally {
            fileClaimManager.releaseFolder(folderKey)
        }
    }

    private suspend fun runWorker(
        workerId: Int,
        folderKey: String,
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
        val primaryProfile = profiles.first()

        for (fileDoc in allFiles) {
            if (isStopRequested || !currentCoroutineContext().isActive || rotationManager.isExhausted) break

            // フォルダ処理上限チェック
            if (config.filesPerFolder > 0 && folderProcessedCounter.get() >= config.filesPerFolder) {
                break
            }

            val fileName = fileDoc.name ?: continue

            // 翻訳済みまたは失敗保持ならスキップ (O(1) キャッシュ照合)
            if (existingOutputNames.contains(fileName) || existingOutputNames.contains("$fileName.failed")) {
                continue
            }

            // 排他クレーム試行 (他ワーカーが着手中ならスキップ)
            if (!fileClaimManager.tryClaimFile(folderKey, fileName)) {
                continue
            }

            val rawContent = readFileContent(fileDoc) ?: continue
            val content = TextCleanser.cleanse(rawContent)

            // 空白・0バイトファイルの即時スキップ
            if (content.isBlank()) {
                val outFile = outputDir.findFile(fileName) ?: outputDir.createFile("text/plain", fileName)
                if (outFile != null) {
                    saveFileContent(outFile, "")
                    existingOutputNames.add(fileName)
                }
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

            // 直前ファイルの原文末尾コンテキストを取得 (未訳の生テキストから直接抽出)
            val prevSourceTail = if (config.enablePrevSrcContext) {
                val fileIdx = allFiles.indexOf(fileDoc)
                if (fileIdx > 0) {
                    val prevFile = allFiles[fileIdx - 1]
                    val prevRaw = readFileContent(prevFile)
                    if (prevRaw != null) {
                        TextCleanser.cleanse(prevRaw).lines().takeLast(config.prevSrcContextLines).joinToString("\n")
                    } else null
                } else null
            } else null

            val workDir = outputDir.findFile(".parts_${fileName}")
            val activeProfile = rotationManager.getCurrentProfile()
            val effectiveSplitThreshold = config.getEffectiveSplitThreshold(sourceLang, activeProfile)
            val effectiveBatchSize = config.getEffectiveBatchSize(sourceLang, activeProfile)

            if (workDir != null || fsize > effectiveSplitThreshold) {
                // 大ファイル: 分割チャンク翻訳 (レジューム対応)
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
                    onLog = { addLog("[W#$workerId] $it") }
                )

                if (success) {
                    existingOutputNames.add(fileName)
                } else {
                    // ユーザー中止時は .failed を作成せず、次回レジューム可能に保持
                    if (!isStopRequested && currentCoroutineContext().isActive) {
                        val failedFileName = "$fileName.failed"
                        val failedFile = outputDir.findFile(failedFileName) ?: outputDir.createFile("text/plain", failedFileName)
                        if (failedFile != null) {
                            saveFileContent(failedFile, content)
                            existingOutputNames.add(failedFileName)
                            addLog("[W#$workerId] ❌ $fileName 全ドライバー失敗 → .failed 保存")
                        }
                    }
                }

                val done = completedCounter.incrementAndGet()
                folderProcessedCounter.incrementAndGet()
                _engineState.update { it.copy(
                    progress = done to totalCount,
                    currentFileName = fileName
                ) }
            } else {
                // 小ファイル: 後続の小ファイルをバッチ上限 (effectiveBatchSize) まで束ねる
                val batchItems = mutableListOf<Pair<DocumentFile, String>>()
                batchItems.add(fileDoc to content)
                var currentBatchBytes = fsize

                val currentIdx = allFiles.indexOf(fileDoc)
                if (currentIdx >= 0) {
                    for (nextIdx in (currentIdx + 1) until allFiles.size) {
                        if (batchItems.size >= 10) break // 1バッチ最大10ファイル
                        val nextDoc = allFiles[nextIdx]
                        val nextName = nextDoc.name ?: continue
                        if (existingOutputNames.contains(nextName) || existingOutputNames.contains("$nextName.failed")) continue
                        val nextWorkDir = outputDir.findFile(".parts_${nextName}")
                        if (nextWorkDir != null) continue

                        // 排他クレーム試行
                        if (!fileClaimManager.tryClaimFile(folderKey, nextName)) continue

                        val nextRaw = readFileContent(nextDoc) ?: continue
                        val nextClean = TextCleanser.cleanse(nextRaw)
                        if (nextClean.isBlank()) {
                            val outF = outputDir.findFile(nextName) ?: outputDir.createFile("text/plain", nextName)
                            if (outF != null) saveFileContent(outF, "")
                            existingOutputNames.add(nextName)
                            completedCounter.incrementAndGet()
                            folderProcessedCounter.incrementAndGet()
                            continue
                        }

                        val nextBytes = nextClean.toByteArray(Charsets.UTF_8).size
                        if (nextBytes > effectiveSplitThreshold || (currentBatchBytes + nextBytes) > effectiveBatchSize) {
                            // 大ファイルまたはバッチ上限超過: 今回のバッチには含めない
                            break
                        }

                        batchItems.add(nextDoc to nextClean)
                        currentBatchBytes += nextBytes
                    }
                }

                if (batchItems.size > 1) {
                    // 2件以上: まとめてバッチ翻訳実行！
                    val batchNames = batchItems.map { it.first.name ?: "" }
                    addLog("[W#$workerId] 📦 バッチ翻訳開始 (${batchItems.size}ファイル / 計:${currentBatchBytes}B / 枠:${effectiveBatchSize}B)")
                    val batchSuccess = translateBatchFiles(
                        batchItems = batchItems,
                        outputDir = outputDir,
                        existingOutputNames = existingOutputNames,
                        rotationManager = rotationManager,
                        sourceLang = sourceLang,
                        novelDict = novelDict,
                        prevSourceTail = prevSourceTail,
                        workerId = workerId
                    )

                    if (batchSuccess) {
                        val done = completedCounter.addAndGet(batchItems.size)
                        folderProcessedCounter.addAndGet(batchItems.size)
                        _engineState.update { it.copy(
                            progress = done to totalCount,
                            currentFileName = batchNames.last()
                        ) }
                    } else {
                        // バッチ失敗時は各ファイルを単体翻訳へフォールバック (フェイルセーフ)
                        addLog("[W#$workerId] ⚠️ バッチ翻訳失敗 → 各ファイルを単体翻訳へフォールバック")
                        for (item in batchItems) {
                            if (isStopRequested || !currentCoroutineContext().isActive) break
                            val singleSuccess = translateSingleFile(
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
                            if (singleSuccess) {
                                existingOutputNames.add(item.first.name ?: "")
                            }
                            val done = completedCounter.incrementAndGet()
                            folderProcessedCounter.incrementAndGet()
                            _engineState.update { it.copy(
                                progress = done to totalCount,
                                currentFileName = item.first.name ?: ""
                            ) }

                            if (config.requestDelaySec > 0) {
                                delay(config.requestDelaySec * 1000L)
                            }
                        }
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

                if (config.requestDelaySec > 0) {
                    delay(config.requestDelaySec * 1000L)
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
    ): Boolean {
        val filePairs = batchItems.map { (doc, text) -> (doc.name ?: "file.txt") to text }
        val combinedInput = BatchTranslator.buildBatchInput(filePairs, config.enableCompletionMarker)
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

            // --- 2. プロンプトループ ---
            for (promptNum in promptList) {
                if (isStopRequested || !currentCoroutineContext().isActive) break

                val prompt = PromptBuilder.buildBatchPrompt(
                    promptNumber = promptNum,
                    fileCount = batchItems.size,
                    customPrompts = config.customPrompts,
                    previousSourceTail = if (config.enablePrevSrcContext) prevSourceTail else null,
                    sourceText = combinedInput,
                    dictionaryStyle = novelDict?.style,
                    dictionaryMap = novelDict?.characters,
                    dictionaryGenders = novelDict?.genders,
                    enableCompletionMarker = config.enableCompletionMarker
                )

                // --- 3. ネットワークリトライループ ---
                var retry = 0
                val maxRetryCount = if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                    rotationManager.poolCapacity.coerceAtLeast(3)
                } else 3

                while (retry < maxRetryCount) {
                    if (isStopRequested || !currentCoroutineContext().isActive) break

                    val activeProfile = rotationManager.getCurrentProfile()
                    val targetProfile = if (profile.provider == LlmProvider.GEMINI) activeProfile else profile

                    val apiResult = callApiForProfile(targetProfile, prompt, combinedInput, rotationManager)

                    when (apiResult) {
                        is LlmApiResult.Success -> {
                            val markerStripped = CompletionMarkerHelper.checkAndStripMarker(apiResult.text, config.enableCompletionMarker)
                            if (markerStripped == null) {
                                addLog("[W#$workerId] ⚠️ バッチ: 完了マーカーなし → 次のプロンプトへ")
                                break
                            }

                            val parsed = BatchTranslator.parseBatchResponse(markerStripped, batchItems.size)
                            if (parsed == null) {
                                addLog("[W#$workerId] ⚠️ バッチ: セグメント分離失敗 (形式不一致/欠落) → 次のプロンプトへ")
                                break
                            }

                            // 各セグメントの品質チェック
                            val (minRatio, maxRatio) = config.getSizeRatioRange(sourceLang)
                            var allSegmentsValid = true
                            for ((idx, item) in batchItems.withIndex()) {
                                val segNum = idx + 1
                                val segText = parsed[segNum] ?: ""
                                val cleanedSeg = TranslationQualityValidator.stripPreamble(segText)
                                val v = TranslationQualityValidator.validate(item.second, cleanedSeg, sourceLang, minRatio, maxRatio)
                                if (v is QualityValidationResult.Failure) {
                                    addLog("[W#$workerId] ⚠️ バッチ[SEG:$segNum]: 品質NG (${v.reason})")
                                    allSegmentsValid = false
                                    break
                                }
                            }

                            if (allSegmentsValid) {
                                parsedSegments = parsed
                                break
                            } else {
                                break
                            }
                        }
                        is LlmApiResult.QuotaExceeded -> {
                            if (targetProfile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                                val advanced = rotationManager.advanceRotation(apiResult.message) { addLog("[W#$workerId]  $it") }
                                if (!advanced) return false
                                retry++
                            } else {
                                delay(5000L)
                                retry++
                            }
                        }
                        is LlmApiResult.NetworkError -> {
                            delay(5000L)
                            retry++
                        }
                        is LlmApiResult.QualityError -> break
                        is LlmApiResult.FatalError -> break
                    }
                }

                if (parsedSegments != null) break
            }

            if (parsedSegments != null) break
        }

        if (parsedSegments != null) {
            for ((idx, item) in batchItems.withIndex()) {
                val segNum = idx + 1
                val fname = item.first.name ?: "file_$segNum.txt"
                val rawSeg = parsedSegments[segNum] ?: ""
                val cleanSeg = TranslationQualityValidator.stripPreamble(rawSeg)
                val outFile = outputDir.findFile(fname) ?: outputDir.createFile("text/plain", fname)
                if (outFile != null) {
                    saveFileContent(outFile, cleanSeg)
                    existingOutputNames.add(fname)
                    addLog("[W#$workerId] ✅ $fname (バッチ保存)")
                }
            }
            return true
        }

        return false
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
                            delay(5000L)
                            retry++
                        }
                        is LlmApiResult.QualityError -> {
                            break
                        }
                        is LlmApiResult.FatalError -> {
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
            if (outFile != null) {
                saveFileContent(outFile, translatedText)
                existingOutputNames.add(fileName)
                addLog("[W#$workerId] ✅ $fileName")
                return true
            }
        } else {
            // 中断された場合は .failed を作成せず次回再開可能に保持
            if (!isStopRequested && currentCoroutineContext().isActive) {
                val failedFileName = "$fileName.failed"
                val failedFile = outputDir.findFile(failedFileName) ?: outputDir.createFile("text/plain", failedFileName)
                if (failedFile != null) {
                    saveFileContent(failedFile, content)
                    existingOutputNames.add(failedFileName)
                    addLog("[W#$workerId] ❌ $fileName (全ドライバー全プロンプト失敗) → .failed")
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
        return when (profile.provider) {
            LlmProvider.GEMINI -> {
                val key = if (config.geminiRotationEnabled) rotationManager.getCurrentKey() else config.geminiApiKeys.firstOrNull() ?: ""
                GeminiApiClient.generateContent(
                    apiKey = key,
                    model = profile.modelName,
                    prompt = prompt,
                    sourceText = sourceText,
                    temperature = profile.temperature,
                    thinkingLevel = profile.thinkingLevel,
                    thinkingBudget = profile.thinkingBudget
                )
            }
            LlmProvider.OPENROUTER -> {
                OpenAiCompatibleClient.chatCompletion(
                    apiKey = config.openRouterApiKey,
                    model = profile.modelName,
                    endpoint = config.openRouterEndpoint,
                    prompt = prompt,
                    sourceText = sourceText,
                    temperature = profile.temperature ?: 0.5,
                    topP = profile.topP ?: 0.9,
                    repetitionPenalty = profile.repetitionPenalty ?: 1.05,
                    providerOrder = profile.providerOrder,
                    providerAllowFallbacks = profile.providerAllowFallbacks,
                    reasoningEffort = profile.reasoningEffort,
                    reasoningEnabled = profile.reasoningEnabled
                )
            }
            LlmProvider.GROQ -> {
                OpenAiCompatibleClient.chatCompletion(
                    apiKey = config.groqApiKey,
                    model = profile.modelName,
                    endpoint = config.groqEndpoint,
                    prompt = prompt,
                    sourceText = sourceText,
                    temperature = profile.temperature ?: 0.5
                )
            }
        }
    }

    private fun readFileContent(doc: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                TextCharsetDetector.readTextAutoDetect(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveFileContent(doc: DocumentFile, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
