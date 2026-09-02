package com.example.novelscraper.translation.llm.pipeline

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.api.GeminiApiClient
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.api.OpenAiCompatibleClient
import com.example.novelscraper.translation.llm.engine.LlmProvider
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.prompt.PromptBuilder
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager
import kotlinx.coroutines.delay

/**
 * 巨大小説テキストを段落・空行境界でチャンク分割し、
 * 中間作業ディレクトリ (.parts_${filename}/in, out) をストレージ上に永続化しながら順次翻訳・結合する。
 */
object LargeFileTranslator {

    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_SEC = 5

    suspend fun translateLargeFile(
        context: Context,
        fileName: String,
        fileContent: String,
        outputDir: DocumentFile,
        config: LlmTranslationConfig,
        rotationManager: LlmRotationManager,
        sourceLang: SourceLanguage,
        dictMap: Map<String, String>? = null,
        dictStyle: String? = null,
        dictGenders: Map<String, String>? = null,
        prevSourceTail: String? = null,
        onLog: (String) -> Unit = {}
    ): Boolean {
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }
        val primaryProfile = profiles.first()
        val chunkSize = primaryProfile.chunkSizeBytes

        // 作業ディレクトリ .parts_${filename}
        val workDirName = ".parts_${fileName}"
        val workDir = outputDir.findFile(workDirName) ?: outputDir.createDirectory(workDirName)
        if (workDir == null) {
            onLog("❌ $fileName : 作業ディレクトリ作成失敗")
            return false
        }

        val inDir = workDir.findFile("in") ?: workDir.createDirectory("in")
        val outDir = workDir.findFile("out") ?: workDir.createDirectory("out")
        if (inDir == null || outDir == null) {
            onLog("❌ $fileName : 作業サブディレクトリ (in/out) 作成失敗")
            return false
        }

        // チャンクファイルが未生成なら分割して in/ に書き出す
        val existingInChunks = inDir.listFiles().filter { it.isFile && it.name?.startsWith("chunk_") == true }
        if (existingInChunks.isEmpty()) {
            val cleansedContent = TextCleanser.cleanse(fileContent)
            val chunks = NovelTextSplitter.splitIntoChunks(
                text = cleansedContent,
                limitBytes = chunkSize,
                prefix = "chunk",
                suffix = ""
            )
            if (chunks.isEmpty()) return false

            for (chunkPair in chunks) {
                val chunkDoc = inDir.createFile("text/plain", chunkPair.first) ?: continue
                writeDocContent(context, chunkDoc, chunkPair.second)
            }
        }

        // チャンクリスト取得 (辞書順ソート = 数値順)
        val chunkDocs = inDir.listFiles()
            .filter { it.isFile && it.name?.startsWith("chunk_") == true }
            .sortedBy { it.name }

        val total = chunkDocs.size
        if (total == 0) {
            onLog("❌ $fileName : チャンクなし")
            return false
        }

        val isResume = outDir.listFiles().any { it.isFile && it.name?.startsWith("chunk_") == true }
        onLog("✂️ $fileName (${fileContent.toByteArray(Charsets.UTF_8).size}B) 分割翻訳 ${if (isResume) "再開" else "開始"} (全${total}チャンク, 枠:${chunkSize}B)")

        var prevTranslatedSummary = ""

        for ((index, chunkDoc) in chunkDocs.withIndex()) {
            val partNum = index + 1
            val chunkName = chunkDoc.name ?: "chunk_${String.format("%04d", partNum)}"

            val outChunk = outDir.findFile(chunkName)
            val outChunkFailed = outDir.findFile("$chunkName.failed")

            // [翻訳済みスキップ]
            if (outChunk != null) {
                val savedText = readDocContent(context, outChunk) ?: ""
                if (savedText.isNotBlank()) {
                    onLog("  ✅ [${partNum}/${total}] $chunkName (翻訳済 / スキップ)")
                    prevTranslatedSummary = savedText.lines().takeLast(20).joinToString("\n")
                    continue
                }
            }

            // [失敗保持スキップ]
            if (outChunkFailed != null) {
                onLog("  ⏭ [${partNum}/${total}] $chunkName.failed → 未解決のため後続チャンクは停止 (手動削除で再翻訳)")
                return false
            }

            val chunkText = readDocContent(context, chunkDoc) ?: continue
            val chunkPrevSrcTail = if (partNum == 1) prevSourceTail else null

            var chunkTranslatedText: String? = null

            // --- 1. ドライバーループ ---
            for ((drvIdx, profile) in profiles.withIndex()) {
                if (drvIdx > 0) {
                    val prevProf = profiles[drvIdx - 1]
                    onLog("  🔄 $chunkName: ${prevProf.modelName} 全失敗 → ${profile.modelName} (${profile.provider.name}) へフォールバック")
                }

                val promptList = profile.promptOrder.ifEmpty { listOf(1, 1) }

                // --- 2. プロンプトループ ---
                for (promptNum in promptList) {
                    val prompt = PromptBuilder.buildPrompt(
                        promptNumber = promptNum,
                        customPrompts = config.customPrompts,
                        previousTranslatedSummary = prevTranslatedSummary,
                        previousSourceTail = chunkPrevSrcTail,
                        sourceText = chunkText,
                        dictionaryStyle = dictStyle,
                        dictionaryMap = dictMap,
                        dictionaryGenders = dictGenders,
                        enableCompletionMarker = config.enableCompletionMarker
                    )

                    val preparedSource = CompletionMarkerHelper.appendMarker(chunkText, config.enableCompletionMarker)

                    // --- 3. ネットワークリトライループ ---
                    var retryCount = 0
                    val maxRetryCount = if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                        (config.geminiApiKeys.size * profiles.size).coerceAtLeast(MAX_RETRIES)
                    } else MAX_RETRIES

                    while (retryCount < maxRetryCount) {
                        onLog("  📦 [${partNum}/${total}] $chunkName (${profile.modelName} / P$promptNum / 試行:${retryCount + 1})")

                        val apiResult = when (profile.provider) {
                            LlmProvider.GEMINI -> {
                                val key = if (config.geminiRotationEnabled) rotationManager.getCurrentKey() else config.geminiApiKeys.firstOrNull() ?: ""
                                GeminiApiClient.generateContent(
                                    apiKey = key,
                                    model = profile.modelName,
                                    prompt = prompt,
                                    sourceText = preparedSource,
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
                                    sourceText = preparedSource,
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
                                    sourceText = preparedSource,
                                    temperature = profile.temperature ?: 0.5
                                )
                            }
                        }

                        when (apiResult) {
                            is LlmApiResult.Success -> {
                                val markerStripped = CompletionMarkerHelper.checkAndStripMarker(apiResult.text, config.enableCompletionMarker)
                                if (markerStripped == null) {
                                    onLog("    ⚠️ $chunkName: 完了マーカーなし (生成途絶疑い) → 次のプロンプトへ")
                                    break
                                }

                                val cleaned = TranslationQualityValidator.stripPreamble(markerStripped)
                                val validation = TranslationQualityValidator.validate(chunkText, cleaned, sourceLang)
                                if (validation is QualityValidationResult.Success) {
                                    chunkTranslatedText = cleaned
                                    break
                                } else if (validation is QualityValidationResult.Failure) {
                                    onLog("    ⚠️ $chunkName: 品質NG (${validation.reason}) → 次のプロンプトへ")
                                    break
                                }
                            }
                            is LlmApiResult.QuotaExceeded -> {
                                onLog("    ⏳ $chunkName: Quota制限検知 (${apiResult.message})")
                                if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                                    rotationManager.advanceRotation { onLog("      $it") }
                                    retryCount++
                                    continue
                                } else {
                                    onLog("    ⏳ 再試行待機 (${RETRY_DELAY_SEC}秒)...")
                                    delay(RETRY_DELAY_SEC * 1000L)
                                    retryCount++
                                }
                            }
                            is LlmApiResult.NetworkError -> {
                                onLog("    ⚠️ $chunkName: ネットワークエラー (${apiResult.message}) → 再試行")
                                delay(RETRY_DELAY_SEC * 1000L)
                                retryCount++
                            }
                            is LlmApiResult.QualityError -> {
                                onLog("    ⚠️ $chunkName: レスポンス品質エラー (${apiResult.reason}) → 次のプロンプトへ")
                                break
                            }
                            is LlmApiResult.FatalError -> {
                                onLog("    ❌ $chunkName: 致命的APIエラー (${apiResult.statusCode} ${apiResult.message})")
                                break
                            }
                        }
                    }

                    if (chunkTranslatedText != null) break
                }

                if (chunkTranslatedText != null) break
            }

            if (chunkTranslatedText != null) {
                val savedChunk = outDir.findFile(chunkName) ?: outDir.createFile("text/plain", chunkName)
                if (savedChunk != null) {
                    writeDocContent(context, savedChunk, chunkTranslatedText)
                }
                onLog("  ✅ [${partNum}/${total}] $chunkName (保存完了)")
                prevTranslatedSummary = chunkTranslatedText.lines().takeLast(20).joinToString("\n")
            } else {
                val failedChunk = outDir.createFile("text/plain", "$chunkName.failed")
                if (failedChunk != null) {
                    writeDocContent(context, failedChunk, chunkText)
                }
                onLog("❌ $chunkName : 全ドライバー失敗 → $chunkName.failed を保存して停止")
                return false
            }

            if (config.requestDelaySec > 0) {
                delay(config.requestDelaySec * 1000L)
            }
        }

        // 全チャンク揃ったか確認
        val allDone = chunkDocs.all { doc ->
            val cName = doc.name ?: return@all false
            val cOut = outDir.findFile(cName)
            cOut != null && (readDocContent(context, cOut)?.isNotBlank() == true)
        }

        if (!allDone) {
            onLog("⚠️ $fileName : 未完了チャンクあり → 作業ディレクトリを保持して次回再開")
            return false
        }

        // チャンク結合
        val combinedSb = StringBuilder()
        for (doc in chunkDocs) {
            val cName = doc.name ?: continue
            val cOut = outDir.findFile(cName) ?: continue
            val text = readDocContent(context, cOut) ?: continue
            if (combinedSb.isNotEmpty()) combinedSb.append("\n\n")
            combinedSb.append(text)
        }

        val finalOutFile = outputDir.findFile(fileName) ?: outputDir.createFile("text/plain", fileName)
        if (finalOutFile != null) {
            writeDocContent(context, finalOutFile, combinedSb.toString())
            onLog("✨ $fileName : 全 ${total} チャンクの分割結合完了")
            workDir.delete()
            return true
        }

        return false
    }

    private fun readDocContent(context: Context, doc: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                TextCharsetDetector.readTextAutoDetect(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDocContent(context: Context, doc: DocumentFile, content: String): Boolean {
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