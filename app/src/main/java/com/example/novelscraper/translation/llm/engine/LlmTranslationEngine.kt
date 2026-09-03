package com.example.novelscraper.translation.llm.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.api.GeminiApiClient
import com.example.novelscraper.translation.llm.api.LlmApiClient
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.api.OpenAiCompatibleClient
import com.example.novelscraper.translation.llm.pipeline.*
import com.example.novelscraper.translation.llm.prompt.PromptBuilder
import com.example.novelscraper.translation.llm.rotation.ApiKeyPoolManager
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        _engineState.value = _engineState.value.copy(
            logs = (_engineState.value.logs + message).takeLast(200)
        )
    }

    fun startTranslation(folderUris: List<Uri>, onCompleted: () -> Unit = {}) {
        if (_engineState.value.isTranslating) return
        isStopRequested = false

        currentJob = coroutineScope.launch(Dispatchers.IO) {
            _engineState.value = _engineState.value.copy(
                isTranslating = true,
                statusText = "翻訳開始準備中...",
                logs = emptyList()
            )
            val workerCount = config.parallelWorkers.coerceIn(1, 6)
            addLog("🚀 LLM 翻訳エンジン起動 (並列ワーカー数: ${workerCount} / 登録モデル: ${config.modelProfiles.size}件)")

            val keyPoolManager = ApiKeyPoolManager(config.geminiApiKeys)

            try {
                for ((folderIndex, folderUri) in folderUris.withIndex()) {
                    if (isStopRequested) break

                    val docFolder = DocumentFile.fromTreeUri(context, folderUri) ?: continue
                    val folderName = docFolder.name ?: "Unknown"

                    val outSubDirName = config.outputSubDir.ifBlank { "翻訳完了_LLM" }
                    _engineState.value = _engineState.value.copy(
                        currentFolderName = folderName,
                        statusText = "フォルダ処理中: $folderName (${folderIndex + 1}/${folderUris.size})"
                    )
                    addLog("----------------------------------------")
                    addLog("📂 フォルダ開始: $folderName")

                    processFolder(docFolder, keyPoolManager)

                    // 物理分割有効時、生成された「分割済み」配下の小説サブフォルダも自動走査
                    if (config.enableTextSplit) {
                        val splitDir = docFolder.findFile("分割済み")
                        if (splitDir != null && splitDir.isDirectory) {
                            val subFolders = splitDir.listFiles().filter { it.isDirectory && it.name != outSubDirName }
                            for (subFolder in subFolders) {
                                if (isStopRequested) break
                                addLog("📂 分割済みサブフォルダ開始: ${subFolder.name}")
                                processFolder(subFolder, keyPoolManager)
                            }
                        }
                    }
                }

                addLog("🛑 全フォルダの処理が完了しました")
            } catch (e: CancellationException) {
                addLog("🛑 ユーザーによる停止")
            } catch (e: Exception) {
                addLog("❌ 予期せぬエラー: ${e.message}")
            } finally {
                fileClaimManager.clear()
                keyPoolManager.reset()
                _engineState.value = _engineState.value.copy(
                    isTranslating = false,
                    statusText = "停止中 / 完了",
                    currentFileName = ""
                )
                onCompleted()
            }
        }
    }

    fun stopTranslation() {
        isStopRequested = true
        currentJob?.cancel()
        _engineState.value = _engineState.value.copy(
            isTranslating = false,
            statusText = "停止中..."
        )
    }

    private suspend fun processFolder(
        folderDoc: DocumentFile,
        keyPoolManager: ApiKeyPoolManager
    ) {
        val outSubDirName = config.outputSubDir.ifBlank { "翻訳完了_LLM" }
        val folderName = folderDoc.name ?: "Unknown"

        // 予約フォルダ自身の再翻訳をガード
        if (folderName == outSubDirName || folderName == "分割済み" || folderName == "翻訳完了") {
            addLog("⏭ 予約フォルダ ($folderName) のためスキップ")
            return
        }

        // 前処理: 物理分割 (有効時)
        if (config.enableTextSplit) {
            TextFilePhysicalSplitter.splitRawNovelFiles(
                context = context,
                inputFolderDoc = folderDoc,
                splitSizeBytes = config.textSplitSizeBytes,
                onLog = { addLog(it) }
            )
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

        // 言語判定 (キャッシュ .lang_cache を確認、無ければ先頭ファイルで判定して保存)
        val langCacheDoc = outputDir.findFile(".lang_cache")
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
            val newCacheDoc = outputDir.createFile("text/plain", ".lang_cache")
            if (newCacheDoc != null) {
                saveFileContent(newCacheDoc, detected.name)
            }
            detected
        }

        val primaryProfile = config.modelProfiles.firstOrNull() ?: ModelProfile(modelName = "gemini-3.5-flash")
        val splitThreshold = config.getEffectiveSplitThreshold(sourceLang, primaryProfile)
        val sizeLog = if (config.enableAutoLanguageSize) " (言語別サイズ自動: ${splitThreshold / 1000}KB)" else " (固定サイズ: ${splitThreshold / 1000}KB)"
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
                val dictModel = config.dictModel.ifBlank { "google/gemma-4-31b-it:free" }
                val dictProvider = config.dictProvider

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

                val mergeModel = config.dictMergeModel.trim().ifBlank { dictModel }

                novelDict = NovelDictionaryGenerator.generate(
                    context = context,
                    folderDoc = folderDoc,
                    sampleFiles = files,
                    provider = dictProvider,
                    apiKeys = dictApiKeys,
                    model = dictModel,
                    mergeModel = mergeModel,
                    endpoint = dictEndpoint,
                    maxBatchBytes = config.dictBatchMaxBytes,
                    maxTotalParts = config.dictTotalParts,
                    sampleMode = config.dictSampleMode,
                    maxTotalScanBytes = config.dictMaxTotalScanBytes,
                    parallelCount = config.dictParallelCount,
                    requestDelaySec = config.dictRequestDelaySec,
                    cooldown429Sec = config.dict429CooldownSec,
                    onLog = { addLog(it) }
                )
            }

            // 【根本治療2】辞書生成が有効なのに辞書が未完成の場合、辞書なしでの翻訳強行を完全遮断して安全スキップ
            if (novelDict == null) {
                addLog("⛔ ${folderDoc.name} : 辞書未完成のため翻訳をスキップ (次回再挑戦)")
                return
            }
        }

        val totalCount = files.size
        val completedCounter = AtomicInteger(0)
        val folderProcessedCounter = AtomicInteger(0)

        // 翻訳済みファイルの事前カウント
        val pendingFiles = mutableListOf<DocumentFile>()
        for (f in files) {
            val fname = f.name ?: continue
            if (outputDir.findFile(fname) != null || outputDir.findFile("$fname.failed") != null) {
                completedCounter.incrementAndGet()
            } else {
                pendingFiles.add(f)
            }
        }

        _engineState.value = _engineState.value.copy(
            progress = completedCounter.get() to totalCount
        )

        val requestedWorkerCount = config.parallelWorkers.coerceIn(1, 6)
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }

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
                    runWorker(
                        workerId = wId,
                        folderName = folderName,
                        allFiles = files,
                        outputDir = outputDir,
                        sourceLang = sourceLang,
                        novelDict = novelDict,
                        completedCounter = completedCounter,
                        folderProcessedCounter = folderProcessedCounter,
                        totalCount = totalCount,
                        rotationManager = rotationManager
                    )
                }
                workerJobs.add(job)
            }

            workerJobs.awaitAll()
        }
    }

    private suspend fun runWorker(
        workerId: Int,
        folderName: String,
        allFiles: List<DocumentFile>,
        outputDir: DocumentFile,
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
            if (isStopRequested || rotationManager.isExhausted) break

            // フォルダ処理上限チェック
            if (config.filesPerFolder > 0 && folderProcessedCounter.get() >= config.filesPerFolder) {
                break
            }

            val fileName = fileDoc.name ?: continue

            // 翻訳済みまたは失敗保持ならスキップ
            if (outputDir.findFile(fileName) != null || outputDir.findFile("$fileName.failed") != null) {
                continue
            }

            // 排他クレーム試行 (他ワーカーが着手中ならスキップ)
            if (!fileClaimManager.tryClaimFile(folderName, fileName)) {
                continue
            }

            val rawContent = readFileContent(fileDoc) ?: continue
            val content = TextCleanser.cleanse(rawContent)

            // 空白・0バイトファイルの即時スキップ
            if (content.isBlank()) {
                val outFile = outputDir.findFile(fileName) ?: outputDir.createFile("text/plain", fileName)
                if (outFile != null) {
                    saveFileContent(outFile, "")
                }
                addLog("[W#$workerId] ⏭ $fileName (空ファイルのためスキップ)")
                val done = completedCounter.incrementAndGet()
                folderProcessedCounter.incrementAndGet()
                _engineState.value = _engineState.value.copy(
                    progress = done to totalCount,
                    currentFileName = fileName
                )
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
            val effectiveSplitThreshold = config.getEffectiveSplitThreshold(sourceLang, primaryProfile)

            if (workDir != null || fsize > effectiveSplitThreshold) {
                // 大ファイル: 分割翻訳 (レジューム対応)
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
                    onLog = { addLog("[W#$workerId] $it") }
                )

                if (!success) {
                    val failedFileName = "$fileName.failed"
                    val failedFile = outputDir.findFile(failedFileName) ?: outputDir.createFile("text/plain", failedFileName)
                    if (failedFile != null) {
                        saveFileContent(failedFile, content)
                        addLog("[W#$workerId] ❌ $fileName 全ドライバー失敗 → .failed 保存")
                    }
                }
            } else {
                // 小ファイル: 単体翻訳
                addLog("[W#$workerId] $fileName (単体処理: ${fsize}B / 専有キー[${rotationManager.getCurrentKeyIndex() + 1}])")
                translateSingleFile(
                    fileDoc = fileDoc,
                    content = content,
                    outputDir = outputDir,
                    rotationManager = rotationManager,
                    sourceLang = sourceLang,
                    novelDict = novelDict,
                    prevSourceTail = prevSourceTail,
                    workerId = workerId
                )
            }

            val done = completedCounter.incrementAndGet()
            folderProcessedCounter.incrementAndGet()
            _engineState.value = _engineState.value.copy(
                progress = done to totalCount,
                currentFileName = fileName
            )
        }
    }

    /**
     * 単体ファイル翻訳
     */
    private suspend fun translateSingleFile(
        fileDoc: DocumentFile,
        content: String,
        outputDir: DocumentFile,
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
            if (drvIdx > 0) {
                val prev = profiles[drvIdx - 1]
                addLog("[W#$workerId] 🔄 $fileName: ${prev.modelName} 全失敗 → ${profile.modelName} (${profile.provider.name}) へフォールバック")
            }

            val promptList = config.getEffectivePromptOrder(sourceLang, profile).ifEmpty { listOf(1, 1) }

            // --- 2. プロンプトループ ---
            for (promptNum in promptList) {
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
                    val apiResult = callApiForProfile(profile, prompt, preparedSource, rotationManager)

                    when (apiResult) {
                        is LlmApiResult.Success -> {
                            val markerStripped = CompletionMarkerHelper.checkAndStripMarker(apiResult.text, config.enableCompletionMarker)
                            if (markerStripped == null) {
                                addLog("[W#$workerId] ⚠️ $fileName: 完了マーカーなし → 次のプロンプトへ")
                                break
                            }
                            val cleaned = TranslationQualityValidator.stripPreamble(markerStripped)
                            val validation = TranslationQualityValidator.validate(content, cleaned, sourceLang)
                            if (validation is QualityValidationResult.Success) {
                                translatedText = cleaned
                                break
                            } else if (validation is QualityValidationResult.Failure) {
                                addLog("[W#$workerId] ⚠️ $fileName: 品質NG (${validation.reason}) → 次のプロンプトへ")
                                break
                            }
                        }
                        is LlmApiResult.QuotaExceeded -> {
                            if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                                val advanced = rotationManager.advanceRotation { addLog("[W#$workerId]  $it") }
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
                addLog("[W#$workerId] ✅ $fileName")
                return true
            }
        } else {
            val failedFileName = "$fileName.failed"
            val failedFile = outputDir.findFile(failedFileName) ?: outputDir.createFile("text/plain", failedFileName)
            if (failedFile != null) {
                saveFileContent(failedFile, content)
                addLog("[W#$workerId] ❌ $fileName (全ドライバー全プロンプト失敗) → .failed")
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