package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.domain.isDeterministicFailure
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.LargeOptions
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.ResidualOptions
import com.example.novelscraper.translation.v2.pipeline.SingleResult
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.TranslateContext
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.pipeline.buildProfilePrompt
import com.example.novelscraper.translation.v2.pipeline.cleanseBasic
import com.example.novelscraper.translation.v2.pipeline.findOrCreateFile
import com.example.novelscraper.translation.v2.pipeline.translateBatch
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.translateSingle
import com.example.novelscraper.translation.v2.pipeline.utf8Bytes
import com.example.novelscraper.translation.v2.pipeline.writeFailed
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

/**
 * 単一ワーカーのファイル走査・翻訳を担う。RunEngineからはフォルダ処理のワーカー段階だけを委譲される。
 * 進捗・状態の反映はコールバック経由（本器はUI状態を持たない）。
 */
class WorkerRunner(
    private val workerId: Int,
    private val store: FileStore,
    private val settings: V2Settings,
    private val options: EngineOptions,
    private val router: PromptRouter,
    private val novelDict: NovelDict?,
    private val completed: AtomicInteger,
    private val total: Int,
    private val batchMaxBytes: Int,
    private val splitThresholdBytes: Int,
    private val chunkSizeBytes: Int,
    private val sourceLang: SourceLang,
    private val promptOrder: List<Int>,
    private val profiles: List<V2ModelProfile>,
    private val profilePromptOrders: Map<String, List<Int>>,
    private val contextTracker: SourceContextTracker,
    private val files: List<VDoc>,
    private val outputDirUri: String,
    private val existing: MutableSet<String>,
    private val claims: MutableSet<String>,
    private val stopped: () -> Boolean = { false },
    private val onFileStart: (fileName: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    private val onProgress: (done: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
    private val onChunkProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {}
) {
    suspend fun run() {
        fun claim(name: String): Boolean = synchronized(claims) { claims.add(name) }
        fun unclaim(name: String) {
            synchronized(claims) { claims.remove(name) }
        }
        fun bump(name: String) {
            val done = completed.incrementAndGet()
            onProgress(done, total, name)
        }
        val (sizeMin, sizeMax) = when (sourceLang) {
            SourceLang.ZH -> settings.sizeRatios.zhMin to settings.sizeRatios.zhMax
            SourceLang.KO -> settings.sizeRatios.koMin to settings.sizeRatios.koMax
            SourceLang.EN -> settings.sizeRatios.enMin to settings.sizeRatios.enMax
            SourceLang.JA -> settings.sizeRatios.jaMin to settings.sizeRatios.jaMax
        }
        val verify = VerifyOptions(
            sizeMinPct = sizeMin,
            sizeMaxPct = sizeMax,
            kanaFloor = options.kanaFloor,
            markerEnabled = options.markerEnabled,
            residual = ResidualOptions(sourceLang)
        )
        val allBasePrompts = options.basePrompts + settings.customPrompts
        val primaryPromptNum = promptOrder.firstOrNull() ?: 1
        // 技術的根拠: 構造化出力の適用範囲をバッチ枠に限定するため、枠種別で送信bindingを使い分ける
        val useJsonBatch = profiles.any { profile ->
            profile.useJsonSchema && when (profile.providerId.toProviderId()) {
                ProviderId.GEMINI -> GEMINI_DESCRIPTOR.capabilitiesFor(profile.model).structuredOutput
                ProviderId.OPENROUTER -> OPENROUTER_DESCRIPTOR.capabilitiesFor(profile.model).structuredOutput
                null -> false
            }
        }
        fun bindCall(forBatch: Boolean): suspend (String, String, String) -> LlmResult {
            return { promptOrDriver, prompt, source ->
                val currentPromptNum = promptOrDriver.toIntOrNull() ?: primaryPromptNum
                val profilePrompts = profiles.associate { profile ->
                    val order = profilePromptOrders[profile.id] ?: promptOrder
                    val targetPromptNum = if (order.contains(currentPromptNum)) currentPromptNum else order.firstOrNull() ?: currentPromptNum
                    profile.id to listOf(
                        buildProfilePrompt(
                            originalPrompt = prompt,
                            basePrompts = allBasePrompts,
                            originalPromptNum = currentPromptNum,
                            targetPromptNum = targetPromptNum
                        )
                    )
                }
                router.execute(listOf(prompt), source, profilePrompts, forBatch)
            }
        }
        val ctx = TranslateContext(
            basePrompts = allBasePrompts,
            promptOrder = promptOrder, // 技術的根拠: translateSingleで品質チェックNG時のプロンプト順序リトライを実行するため設定順序を渡す
            driverNames = listOf("w$workerId"),
            dictionary = novelDict,
            verify = verify,
            call = bindCall(false),
            callBatch = bindCall(true),
            batchJsonFormat = useJsonBatch,
            prevContextLines = settings.prevContext.lines,
            prevContextEnabled = settings.prevContext.enabled,
            maxSameRetries = 0,
            stopped = stopped,
            meter = null,
            log = { log("[W#$workerId] $it") }
        )
        for ((fileIdx, file) in files.withIndex()) {
            if (stopped() || router.exhausted) break
            val fileName = file.name
            if (existing.contains(fileName) || existing.contains("${fileName}.failed")) continue
            if (!claim(fileName)) continue
            onFileStart(fileName, completed.get(), total)

            var retainPrimaryClaim = false
            try {
                val raw = store.readText(file.uri)
                if (raw == null) {
                    log("❌ [W#$workerId] ファイル読込失敗: $fileName")
                    writeFailed(store, outputDirUri, fileName, "unreadable file")
                    existing.add("${fileName}.failed")
                    bump(fileName)
                    continue
                }
                val content = cleanseBasic(raw)
                contextTracker.putSource(fileIdx, content)
                if (content.isBlank()) {
                    val out = findOrCreateFile(store, outputDirUri, fileName, "text/plain")
                    if (out != null && store.writeText(out.uri, "")) existing.add(fileName)
                    bump(fileName)
                    continue
                }
                val contentBytes = utf8Bytes(content)

                if (contentBytes > splitThresholdBytes) {
                    if (contentBytes > options.maxInputBytes) {
                        // 技術的根拠1行：上限超えの巨大入力は実行時分割も事前分割への自動回送もせず、その場でスキップ確定する
                        val mb = options.maxInputBytes / 1_000_000
                        log("⏭️ [W#$workerId] 上限超過(${mb}MB)のためスキップ: $fileName (${contentBytes}B)。設定で「物理分割」を有効にしてください")
                        writeFailed(store, outputDirUri, fileName, content) { log(it) }
                        existing.add("$fileName.failed")
                        bump(fileName)
                        continue
                    }
                    val workDir = store.findChild(outputDirUri, ".parts_${fileName}")
                        ?: store.createDir(outputDirUri, ".parts_${fileName}")
                    if (workDir == null) {
                        log("❌ [W#$workerId] 大ファイル用作業フォルダ作成失敗: $fileName")
                        writeFailed(store, outputDirUri, fileName, content) { log(it) }
                        existing.add("$fileName.failed")
                        bump(fileName)
                    } else {
                        log("📦 [W#$workerId] 大ファイル分割翻訳開始: $fileName (${contentBytes}B)")
                        val ok = try {
                            translateLarge(
                                store, workDir.uri, outputDirUri, fileName, content, ctx,
                                LargeOptions(
                                    chunkSizeBytes = chunkSizeBytes,
                                    tailLines = settings.prevContext.lines.coerceIn(
                                        TranslationLimits.PREV_LINES_RANGE.first,
                                        TranslationLimits.PREV_LINES_RANGE.last
                                    ),
                                    maxInputBytes = options.maxInputBytes
                                ),
                                prevSourceTail = contextTracker.getPrevSourceTail(fileIdx),
                                onChunkProgress = { cur, tot ->
                                    onChunkProgress(cur, tot)
                                }
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            // 技術的根拠: 分割処理中の例外でワーカーを死なせず、.failedを記録して他ワーカーの連鎖死を防ぎ次へ進む
                            log("❌ [W#$workerId] 大ファイル分割翻訳で予期せぬ例外: $fileName (${t.javaClass.simpleName}: ${t.message})")
                            writeFailed(store, outputDirUri, fileName, content) { log(it) }
                            existing.add("$fileName.failed")
                            bump(fileName)
                            false
                        }
                        onChunkProgress(0, 0)
                        if (ok) {
                            existing.add(fileName)
                            log("✅ [W#$workerId] 大ファイル翻訳完了: $fileName")
                            bump(fileName)
                        }
                    }
                    continue
                }

                // バッチ束ね（後続のみ・最大件数・合計バイト上限）
                val batch = mutableListOf(file to content)
                // 各話の直前話末尾（全体文脈基準。フォールバック時の単体翻訳と同一にする）
                val batchPrevTails = mutableListOf(contextTracker.getPrevSourceTail(fileIdx))
                var batchBytes = contentBytes
                for (nextIdx in fileIdx + 1 until files.size) {
                    if (batch.size >= options.batchMaxFiles) break
                    val next = files[nextIdx]
                    val nextName = next.name
                    if (existing.contains(nextName) || existing.contains("$nextName.failed")) continue
                    if (existing.contains(".parts_${nextName}")) continue
                    if (!claim(nextName)) continue
                    val nextRaw = store.readText(next.uri)
                    if (nextRaw == null) {
                        unclaim(nextName)
                        continue
                    }
                    val nextClean = cleanseBasic(nextRaw)
                    contextTracker.putSource(nextIdx, nextClean)
                    if (nextClean.isBlank()) {
                        val out = findOrCreateFile(store, outputDirUri, nextName, "text/plain")
                        if (out != null && store.writeText(out.uri, "")) existing.add(nextName)
                        bump(nextName)
                        unclaim(nextName)
                        continue
                    }
                    val nextBytes = utf8Bytes(nextClean)
                    // 技術的根拠: バッチ束ね判定でも固定値ではなく最小モデル連動の動的 splitThresholdBytes を適用しトークン溢れを防止
                    if (nextBytes > splitThresholdBytes || batchBytes + nextBytes > batchMaxBytes) {
                        unclaim(nextName)
                        break
                    }
                    batch.add(next to nextClean)
                    batchPrevTails.add(contextTracker.getPrevSourceTail(nextIdx))
                    batchBytes += nextBytes
                }
                try {
                    if (batch.size > 1) {
                        log("📦 [W#$workerId] バッチ翻訳開始 (${batch.size}件): ${batch.map { it.first.name }.joinToString(", ")}")
                        val outcome = translateBatch(
                            store, outputDirUri,
                            batch.map { it.first.name to it.second },
                            ctx,
                            prevSourceTail = batchPrevTails.firstOrNull(),
                            itemPrevTails = batchPrevTails
                        )
                        if (outcome.settled > 0) {
                            val done = completed.addAndGet(outcome.settled)
                            onProgress(done, total, fileName)
                            log("✅ [W#$workerId] バッチ翻訳完了 (${outcome.savedFiles.size}件保存)")
                        }
                        // 保存系はstage内で完結するため、結果から既存集合を直接同期（不要なfindChild Binder IPCクエリを全廃）
                        // 技術的根拠: SAFの findChild は O(N) の線形ディレクトリ走査・IPCクエリを伴うため、translateBatch の確定ファイル名で1工程同期する
                        existing.addAll(outcome.savedFiles)
                        if (outcome.configBlocked) {
                            log("⚠️ [W#$workerId] 設定エラーのため終了します（修正後に再実行可能）")
                            return
                        }
                    } else {
                        log("📝 [W#$workerId] 翻訳中: $fileName")
                        when (val r = translateSingle(content, ctx, prevSourceTail = contextTracker.getPrevSourceTail(fileIdx))) {
                            is SingleResult.Translated -> {
                                val out = findOrCreateFile(store, outputDirUri, fileName, "text/plain")
                                if (out != null && store.writeText(out.uri, r.text)) {
                                    existing.add(fileName)
                                    log("✅ [W#$workerId] 翻訳完了・保存: $fileName")
                                    bump(fileName)
                                }
                            }
                            is SingleResult.Failed -> {
                                if (r.isDeterministic || isDeterministicFailure(r.terminal, r.note)) {
                                    log("❌ [W#$workerId] 翻訳失敗: $fileName (${r.terminal} ${r.note})".trim())
                                    // 技術的根拠1行：確定的エラーのみ.failedを作成し、後続ワーカーの拾い直しを防ぐ。
                                    if (writeFailed(store, outputDirUri, fileName, content) { log(it) }) {
                                        existing.add("$fileName.failed")
                                        bump(fileName)
                                    }
                                } else {
                                    log("⚠️ [W#$workerId] 一時エラー/制限のため未完了として保留します: $fileName (${r.terminal} ${r.note})".trim())
                                    retainPrimaryClaim = true // 技術的根拠1行：同一セッション内で後続ワーカーが重複処理しないようclaimを保持する
                                }
                            }
                            is SingleResult.ConfigOnly -> {
                                // 技術的根拠1行：設定不良は全ファイル共通のため残件を無駄打ちせずワーカー終了する（バッチconfigBlockedと対称）。
                                log("⚠️ [W#$workerId] 設定エラーのため終了します（修正後に再実行可能）")
                                return
                            }
                            is SingleResult.Stopped -> return
                        }
                    }
                } finally {
                    for (item in batch.drop(1)) {
                        unclaim(item.first.name)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 技術的根拠: ファイル走査・処理中の予期せぬ例外でワーカーを死なせず、.failedを記録して他ワーカーの連鎖死を防ぎ次へ進む
                log("❌ [W#$workerId] ファイル処理中に予期せぬ例外: $fileName (${t.javaClass.simpleName}: ${t.message})")
                val fallbackContent = try { store.readText(file.uri) ?: "" } catch (_: Throwable) { "" }
                writeFailed(store, outputDirUri, fileName, fallbackContent) { log(it) }
                existing.add("$fileName.failed")
                bump(fileName)
            } finally {
                if (!retainPrimaryClaim) unclaim(fileName)
            }
        }
    }
}
