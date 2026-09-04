package com.example.novelscraper.translation.llm.pipeline

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.api.LlmRequestRunner
import com.example.novelscraper.translation.llm.api.LlmRetryPolicy
import com.example.novelscraper.translation.llm.engine.LlmProvider
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.prompt.PromptBuilder
import com.example.novelscraper.translation.llm.rotation.ApiKeyPoolManager
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
        isStopRequested: () -> Boolean = { false },
        onLog: (String) -> Unit = {},
        onChunkProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Boolean {
        val profiles = config.modelProfiles.ifEmpty { listOf(ModelProfile(modelName = "gemini-3.5-flash")) }
        val activeProfile = rotationManager.getCurrentProfile()
        val chunkSize = config.getEffectiveChunkSize(sourceLang, activeProfile)

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

            var createdChunks = 0
            for (chunkPair in chunks) {
                val chunkDoc = inDir.createFile("text/plain", chunkPair.first)
                if (chunkDoc == null) {
                    onLog("❌ $fileName : チャンクファイル作成失敗 (${chunkPair.first})")
                    continue
                }
                if (writeDocContent(context, chunkDoc, chunkPair.second)) {
                    createdChunks++
                } else {
                    onLog("❌ $fileName : チャンク書き込み失敗 (${chunkPair.first})")
                }
            }
            if (createdChunks != chunks.size) {
                onLog("❌ $fileName : チャンク生成不全 (${createdChunks}/${chunks.size}) → 次回再試行")
                return false
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
        var outNames = outDir.listFiles().mapNotNull { it.name }.toMutableSet()

        for ((index, chunkDoc) in chunkDocs.withIndex()) {
            if (isStopRequested() || !currentCoroutineContext().isActive) {
                onLog("🛑 $fileName : 中止要求検知 → チャンク作業状態を保持して安全終了")
                return false
            }

            val partNum = index + 1
            val chunkName = chunkDoc.name ?: "chunk_${String.format("%04d", partNum)}"

            val outChunk = if (outNames.contains(chunkName)) outDir.findFile(chunkName) else null
            val outChunkFailed = if (outNames.contains("$chunkName.failed")) outDir.findFile("$chunkName.failed") else null

            // [翻訳済みスキップ]
            if (outChunk != null) {
                val savedText = readDocContent(context, outChunk) ?: ""
                if (savedText.isNotBlank()) {
                    onLog("  ✅ [${partNum}/${total}] $chunkName (翻訳済 / スキップ)")
                    prevTranslatedSummary = savedText.lines().takeLast(20).joinToString("\n")
                    onChunkProgress(partNum, total)
                    continue
                }
            }

            // [失敗保持スキップ]
            if (outChunkFailed != null) {
                onLog("  ⏭ [${partNum}/${total}] $chunkName.failed → 未解決のため後続チャンクは停止 (手動削除で再翻訳)")
                return false
            }

            val chunkText = readDocContent(context, chunkDoc)
            if (chunkText == null) {
                onLog("❌ $chunkName : チャンク読み取り失敗 → 作業保持して次回再試行")
                return false
            }
            val chunkPrevSrcTail = if (partNum == 1) prevSourceTail else null

            var chunkTranslatedText: String? = null

            // --- 1. ドライバーループ ---
            for ((drvIdx, profile) in profiles.withIndex()) {
                if (isStopRequested() || !currentCoroutineContext().isActive) break

                if (drvIdx > 0) {
                    val prevProf = profiles[drvIdx - 1]
                    onLog("  🔄 $chunkName: ${prevProf.modelName} 全失敗 → ${profile.modelName} (${profile.provider.name}) へフォールバック")
                }

                val promptList = config.getEffectivePromptOrder(sourceLang, profile).ifEmpty { listOf(1, 1) }

                // --- 2. プロンプトループ ---
                for (promptNum in promptList) {
                    if (isStopRequested() || !currentCoroutineContext().isActive) break

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
                    // 分間制限疑いの429は同モデル再試行を優先し、即時のモデル切替を避ける
                    var sameModelQuotaRetries = 0
                    var retryCount = 0
                    val maxRetryCount = if (profile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                        (config.geminiApiKeys.size * profiles.size).coerceAtLeast(MAX_RETRIES)
                    } else MAX_RETRIES

                    while (retryCount < maxRetryCount) {
                        if (isStopRequested() || !currentCoroutineContext().isActive) break

                        val activeProfile = rotationManager.getCurrentProfile()
                        val targetProfile = if (profile.provider == LlmProvider.GEMINI) activeProfile else profile

                        onLog("  📦 [${partNum}/${total}] $chunkName (${targetProfile.modelName} / P$promptNum / 試行:${retryCount + 1})")

                        val apiResult = LlmRequestRunner.callForProfile(
                            config = config,
                            rotationManager = rotationManager,
                            profile = targetProfile,
                            prompt = prompt,
                            sourceText = preparedSource
                        )

                        when (apiResult) {
                            is LlmApiResult.Success -> {
                                val markerStripped = CompletionMarkerHelper.checkAndStripMarker(apiResult.text, config.enableCompletionMarker)
                                if (markerStripped == null) {
                                    onLog("    ⚠️ $chunkName: 完了マーカーなし (生成途絶疑い) → 次のプロンプトへ")
                                    break
                                }

                                val cleaned = TranslationQualityValidator.stripPreamble(markerStripped)
                                val (minRatio, maxRatio) = config.getSizeRatioRange(sourceLang)
                                val validation = TranslationQualityValidator.validate(chunkText, cleaned, sourceLang, minRatio, maxRatio)
                                if (validation is QualityValidationResult.Success) {
                                    chunkTranslatedText = cleaned
                                    break
                                } else if (validation is QualityValidationResult.Failure) {
                                    onLog("    ⚠️ $chunkName: 品質NG (${validation.reason}) → 次のプロンプトへ")
                                    break
                                }
                            }
                            is LlmApiResult.QuotaExceeded -> {
                                onLog("    ⏳ $chunkName: Quota制限検知 (${apiResult.message.take(200)})")
                                if (targetProfile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled &&
                                    !ApiKeyPoolManager.isDailyQuotaExceeded(apiResult.message) &&
                                    sameModelQuotaRetries < LlmRetryPolicy.MAX_SAME_MODEL_QUOTA_RETRIES
                                ) {
                                    sameModelQuotaRetries++
                                    val waitSec = LlmRetryPolicy.quotaSameModelWaitSec(apiResult.retryAfterSec)
                                    onLog("    ⏳ $chunkName: 分間制限疑い → ${waitSec}秒待機して同モデル再試行 [${sameModelQuotaRetries}/${LlmRetryPolicy.MAX_SAME_MODEL_QUOTA_RETRIES}]")
                                    delay(waitSec * 1000L)
                                    retryCount++
                                    continue
                                }
                                if (targetProfile.provider == LlmProvider.GEMINI && config.geminiRotationEnabled) {
                                    val advanced = rotationManager.advanceRotation(apiResult.message) { onLog("      $it") }
                                    if (!advanced) {
                                        return false
                                    }
                                    retryCount++
                                    continue
                                } else {
                                    onLog("    ⏳ 再試行待機 (${RETRY_DELAY_SEC}秒)...")
                                    delay(RETRY_DELAY_SEC * 1000L)
                                    retryCount++
                                }
                            }
                            is LlmApiResult.NetworkError -> {
                                if (retryCount + 1 >= maxRetryCount) {
                                    onLog("    ⚠️ $chunkName: ネットワークエラー (${apiResult.message}) → 再試行上限到達")
                                } else {
                                    val waitMs = LlmRetryPolicy.backoffDelayMs(retryCount)
                                    onLog("    ⚠️ $chunkName: ネットワークエラー (${apiResult.message}) → ${(waitMs / 1000)}秒後再試行")
                                    delay(waitMs)
                                }
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
                if (savedChunk != null && writeDocContent(context, savedChunk, chunkTranslatedText)) {
                    outNames.add(chunkName)
                    onLog("  ✅ [${partNum}/${total}] $chunkName (保存完了)")
                    prevTranslatedSummary = chunkTranslatedText.lines().takeLast(20).joinToString("\n")

                    onChunkProgress(partNum, total)
                } else {
                    onLog("❌ $chunkName : チャンク保存失敗 → 作業保持して次回再試行")
                    return false
                }
            } else {
                // 中断された場合は .failed を作成せず、作業状態を保持して次回再開できるようにする
                if (isStopRequested() || !currentCoroutineContext().isActive) {
                    onLog("🛑 $chunkName : 停止要求により中断 (中間状態保持)")
                    return false
                }
                val failedChunk = outDir.findFile("$chunkName.failed") ?: outDir.createFile("text/plain", "$chunkName.failed")
                if (failedChunk != null && writeDocContent(context, failedChunk, chunkText)) {
                    outNames.add("$chunkName.failed")
                }
                onLog("❌ $chunkName : 全ドライバー失敗 → $chunkName.failed を保存して停止")
                return false
            }
        }

        // 中止要求があった場合は結合処理へ進まない
        if (isStopRequested() || !currentCoroutineContext().isActive) {
            return false
        }

        // 全チャンク揃ったか確認 (SAF IPC連打を防ぐためマップ化)
        val outDocMap = outDir.listFiles().associateBy { it.name }
        val allDone = chunkDocs.all { doc ->
            val cName = doc.name ?: return@all false
            val cOut = outDocMap[cName]
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
            val cOut = outDocMap[cName] ?: continue
            val text = readDocContent(context, cOut) ?: continue
            if (combinedSb.isNotEmpty()) {
                // チャンク境界の余分な空行増殖を防止: 前のテキスト末尾の改行と重複しないよう調整
                val prevEndsWithNewline = combinedSb.endsWith("\n")
                combinedSb.append(if (prevEndsWithNewline) "\n" else "\n\n")
            }
            combinedSb.append(text)
        }

        val finalOutFile = outputDir.findFile(fileName) ?: outputDir.createFile("text/plain", fileName)
        if (finalOutFile != null) {
            if (writeDocContent(context, finalOutFile, combinedSb.toString())) {
                onLog("✨ $fileName : 全 ${total} チャンクの分割結合完了")
                deleteDirectoryRecursively(workDir)
                return true
            }
            onLog("❌ $fileName : 結合結果の保存失敗 → チャンクデータを保全して終了")
            return false
        }

        return false
    }

    private fun deleteDirectoryRecursively(dir: DocumentFile) {
        try {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    deleteDirectoryRecursively(child)
                } else {
                    child.delete()
                }
            }
            dir.delete()
        } catch (_: Exception) {}
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
