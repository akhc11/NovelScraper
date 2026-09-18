package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProgressObserver
import com.example.novelscraper.translation.v2.domain.ProviderRegistry
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.infra.AtomicFileGateway
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.BundlePool
import com.example.novelscraper.translation.v2.pipeline.LargeOptions
import com.example.novelscraper.translation.v2.pipeline.LargeOutcome
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.ResidualOptions
import com.example.novelscraper.translation.v2.pipeline.SingleResult
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.TranslateContext
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.pipeline.PromptSpec
import com.example.novelscraper.translation.v2.pipeline.assemblePrompt
import com.example.novelscraper.translation.v2.pipeline.requireHeadText
import com.example.novelscraper.translation.v2.pipeline.cleanseBasic
import com.example.novelscraper.translation.v2.pipeline.RefineConfig
import com.example.novelscraper.translation.v2.pipeline.resolveRefinePrompt
import com.example.novelscraper.translation.v2.pipeline.shouldPersistFailed
import com.example.novelscraper.translation.v2.pipeline.translateBatch
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.translateSingle
import com.example.novelscraper.translation.v2.pipeline.utf8Bytes
import com.example.novelscraper.translation.v2.pipeline.writeFailed
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.buildRefineProfileOverrides
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
    private val pool: BundlePool,
    private val stopped: () -> Boolean = { false },
    private val onFileStart: (fileName: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    private val onProgress: ProgressObserver = { _, _, _ -> },
    private val onChunkProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {}
) {
    suspend fun run() {
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
        // 技術的根拠: 構造化出力の適用範囲をバッチ枠に限定するため、枠種別で送信bindingを使い分ける
        val useJsonBatch = profiles.any { profile ->
            // 技術的根拠1行：能力判定を登録簿に一本化し、未知プロバイダーは非対応扱いにする（従来のnull->falseと同一）。
            profile.useJsonSchema &&
                ProviderRegistry.capabilitiesForOrNull(profile.providerId, profile.model)?.structuredOutput == true
        }
        fun bindCall(
            forBatch: Boolean,
            profileOverrides: Map<String, V2ModelProfile>? = null
        ): suspend (String, PromptSpec, String) -> LlmResult {
            return { _, spec, source ->
                // 技術的根拠1行：番号差し替えを文字列手術にしないため、Spec 複写＋再描画に寄せる（Router 型は不変）。
                val primaryPrompt = assemblePrompt(spec)
                val profilePrompts = profiles.associate { profile ->
                    if (spec.headNum < 0) {
                        // 技術的根拠1行：推敲等の固定文は頭差し替えの対象外とし、全プロファイル同一文にする（従来の予備路による混入をなくす）。
                        profile.id to listOf(primaryPrompt)
                    } else {
                        val order = profilePromptOrders[profile.id] ?: promptOrder
                        val targetPromptNum = if (order.contains(spec.headNum)) spec.headNum else order.firstOrNull() ?: spec.headNum
                        val targeted = if (targetPromptNum == spec.headNum) {
                            spec
                        } else {
                            spec.copy(
                                headNum = targetPromptNum,
                                headText = requireHeadText(allBasePrompts, targetPromptNum)
                            )
                        }
                        profile.id to listOf(assemblePrompt(targeted))
                    }
                }
                router.execute(listOf(primaryPrompt), source, profilePrompts, forBatch, profileOverrides)
            }
        }
        // 技術的根拠1行：磨き送り口は主経路の別名としてここで作り、番号解釈の抜け道を翻訳路に持ち込まない。
        // 技術的根拠1行：推敲の思考上書きは口の差分引数に乗せ、巡回器の二重化（クォータ二重管理）を避ける。
        val batchCall = bindCall(true)
        val refineOverrides = buildRefineProfileOverrides(profiles, settings.refine)
            .ifEmpty { null }
        val refineCall: (suspend (String, String) -> LlmResult)? =
            if (settings.refine.enabled) {
                val refineSender = bindCall(forBatch = false, profileOverrides = refineOverrides)
                val sender: suspend (String, String) -> LlmResult =
                    { prompt, source ->
                        // 技術的根拠1行：推敲文は頭差し替え対象外のため、固定文番(-1)の Spec で送る。
                        refineSender("w$workerId", PromptSpec(headNum = -1, headText = prompt, blocks = emptyList()), source)
                    }
                sender
            } else null
        val ctx = TranslateContext(
            basePrompts = allBasePrompts,
            promptOrder = promptOrder, // 技術的根拠: translateSingleで品質チェックNG時のプロンプト順序リトライを実行するため設定順序を渡す
            driverNames = listOf("w$workerId"),
            dictionary = novelDict,
            verify = verify,
            sourceLang = sourceLang,
            refine = refineCall?.let { RefineConfig(resolveRefinePrompt(settings.refine.prompt), it) },
            call = bindCall(false),
            callBatch = batchCall,
            batchJsonFormat = useJsonBatch,
            profileMemoEnabled = settings.dict.profileMemoEnabled,
            prevContextLines = settings.prevContext.lines,
            prevContextEnabled = settings.prevContext.enabled,
            maxSameRetries = 0,
            stopped = stopped,
            meter = null,
            log = { log("[W#$workerId] $it") }
        )
        // 技術的根拠1行：保存手順は gateway 口に一本化し、直書き経路を作らない（Composition Root での配線）。
        val gateway = AtomicFileGateway(store) { log(it) }

        // 1件の単品翻訳。false＝ワーカー終了。
        // 技術的根拠1行：単品路の実体はここだけに置き、束ね路と単独路での扱い違いをなくす。
        suspend fun processSingle(fileIdx: Int, fileName: String, content: String): Boolean {
            log("📝 [W#$workerId] 翻訳中: $fileName")
            when (val r = translateSingle(content, ctx, prevSourceTail = contextTracker.getPrevSourceTail(fileIdx))) {
                is SingleResult.Translated -> {
                    if (gateway.saveVerified(outputDirUri, fileName, r.text) != null) {
                        existing.add(fileName)
                        log("✅ [W#$workerId] 翻訳完了・保存: $fileName")
                        bump(fileName)
                    }
                }
                is SingleResult.Failed -> {
                    if (shouldPersistFailed(r)) {
                        log("❌ [W#$workerId] 翻訳失敗: $fileName (${r.terminal} ${r.note})".trim())
                        // 技術的根拠1行：確定的エラーのみ.failedを作成し、後続ワーカーの拾い直しを防ぐ。
                        if (writeFailed(store, outputDirUri, fileName, content) { log(it) }) {
                            existing.add("$fileName.failed")
                            bump(fileName)
                        }
                    } else {
                        log("⚠️ [W#$workerId] 一時エラー/制限のため未完了として保留します: $fileName (${r.terminal} ${r.note})".trim())
                    }
                }
                is SingleResult.ConfigOnly -> {
                    // 技術的根拠1行：設定不良は全ファイル共通のため残件を無駄打ちせずワーカー終了する（バッチconfigBlockedと対称）。
                    log("⚠️ [W#$workerId] 設定エラーのため終了します（修正後に再実行可能）")
                    return false
                }
                is SingleResult.Stopped -> return false
            }
            return true
        }

        // 大ファイル1件。上限超過の確定・作業所なしの保留・完了/保持/確定失敗の扱いは従来通り。
        suspend fun processLarge(fileIdx: Int, fileName: String, content: String, contentBytes: Int) {
            if (contentBytes > options.maxInputBytes) {
                // 技術的根拠1行：上限超えの巨大入力は実行時分割も事前分割への自動回送もせず、その場でスキップ確定する
                val mb = options.maxInputBytes / 1_000_000
                log("⏭️ [W#$workerId] 上限超過(${mb}MB)のためスキップ: $fileName (${contentBytes}B)。設定で「物理分割」を有効にしてください")
                writeFailed(store, outputDirUri, fileName, content) { log(it) }
                existing.add("$fileName.failed")
                bump(fileName)
                return
            }
            val workDir = store.findChild(outputDirUri, ".parts_${fileName}")
                ?: store.createDir(outputDirUri, ".parts_${fileName}")
            if (workDir == null) {
                // 技術的根拠1行：作業所作成失敗はSAF/I-O等の外的要因のため保留し.failedを作らない（確定は終端Dead経路のみ）。
                // 動作例：保存先の権限が一時的に外れる→保持のまま次へ、権限復旧後の次回に再開する。
                log("⚠️ [W#$workerId] 大ファイル用作業フォルダ作成失敗のため保留します: $fileName（次回再試行）")
            } else {
                log("📦 [W#$workerId] 大ファイル分割翻訳開始: $fileName (${contentBytes}B)")
                val outcome: LargeOutcome? = try {
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
                    // 技術的根拠1行：不変条件3（塊の途中失敗では親に.failedを作らない）を順守し、ワーカー例外をログ記録して同一セッション内の連鎖死を防ぐ
                    log("❌ [W#$workerId] 大ファイル分割翻訳で予期せぬ例外: $fileName (${t.javaClass.simpleName}: ${t.message})")
                    null
                }
                onChunkProgress(0, 0)
                when (outcome) {
                    is LargeOutcome.Completed -> {
                        existing.add(fileName)
                        log("✅ [W#$workerId] 大ファイル翻訳完了: $fileName")
                        bump(fileName)
                    }
                    is LargeOutcome.Held -> {
                        log("⏸ [W#$workerId] 未完了のため保持します: $fileName (${outcome.completedChunks}/${outcome.totalChunks} chunks完了。理由: ${outcome.reason}。同一セッション内では再処理しません。次回実行で再開します）")
                    }
                    is LargeOutcome.Dead -> {
                        // 技術的根拠1行：再開成功があり得ない終端は単体経路の確定失敗と同一に扱い、親失敗記録に回して可視化する。
                        log("❌ [W#$workerId] 大ファイル翻訳を確定失敗とします: $fileName (理由: ${outcome.reason})")
                        if (writeFailed(store, outputDirUri, fileName, "large-file dead: ${outcome.reason}") { log(it) }) {
                            existing.add("$fileName.failed")
                            bump(fileName)
                            store.deleteRecursively(workDir.uri)
                        }
                    }
                    else -> {
                        // 中断・予期せぬ例外時は保持のまま次へ
                    }
                }
            }
        }

        // 1件処理（読込→空白→上限→大→単）。false＝ワーカー終了。
        suspend fun processOne(fileIdx: Int, preRead: String?): Boolean {
            val file = files[fileIdx]
            val fileName = file.name
            onFileStart(fileName, completed.get(), total)
            try {
                val raw = preRead ?: store.readText(file.uri)
                if (raw == null) {
                    // 技術的根拠1行：業界標準(Retry-After/5xx/429率は再送、400/認証/課金は確定)と仕様書§4.5/§7-9通り、I/O読込失敗は外的要因として保留し.failedを作らない。
                    // 動作例：地下鉄でネット切断→「読込失敗のため保留、次回自動再試行」と残り、次回起動で訳し直す（.failed化してスキップ確定しない）。
                    log("⚠️ [W#$workerId] ファイル読込失敗のため保留します: $fileName（次回再試行）")
                    return true
                }
                val content = cleanseBasic(raw)
                contextTracker.putCleanSource(fileIdx, content)
                if (content.isBlank()) {
                    if (gateway.saveVerified(outputDirUri, fileName, "") != null) existing.add(fileName)
                    bump(fileName)
                    return true
                }
                val contentBytes = utf8Bytes(content)
                if (contentBytes > splitThresholdBytes) {
                    processLarge(fileIdx, fileName, content, contentBytes)
                    return true
                }
                return processSingle(fileIdx, fileName, content)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 技術的根拠1行：I/O系の予期せぬ例外は外的要因として保留し、原因不明のバグ系のみ.failed確定する（429/5xxは再送・400/認証は確定の業界標準に準拠）。
                // 動作例：保存中にSD抜去→「一時エラーのため保留」と残り次回再試行。プログラムバグ→「予期せぬ例外」として.failed化し連鎖死だけ防ぐ。
                // 技術的根拠1行：診断は注入済みのログ口に一本化し、Android枠組みへの直接依存を持たない。
                log("⚠️ [W#$workerId] unexpected file error: $fileName (${t.javaClass.simpleName}: ${t.message})")
                if (isTransientStorageError(t)) {
                    log("⚠️ [W#$workerId] 一時エラーのため保留します: $fileName (${t.javaClass.simpleName}。次回再試行）")
                } else {
                    log("❌ [W#$workerId] ファイル処理中に予期せぬ例外: $fileName (${t.javaClass.simpleName}: ${t.message})")
                    val fallbackContent = try { store.readText(file.uri) ?: "" } catch (e: Throwable) {
                        log("⚠️ [W#$workerId] fallback read failed: $fileName (${e.javaClass.simpleName})")
                        ""
                    }
                    writeFailed(store, outputDirUri, fileName, fallbackContent) { log(it) }
                    existing.add("$fileName.failed")
                    bump(fileName)
                }
                return true
            }
        }

        // 束処理（連番束）。束の顔ぶれは計画が決めるため再走査しない。false＝ワーカー終了。
        suspend fun processBundle(indices: List<Int>): Boolean {
            data class Member(val idx: Int, val file: VDoc, val raw: String)
            try {
                val batch = mutableListOf<Member>()
                val batchPrevTails = mutableListOf<String?>()
                for (idx in indices) {
                    if (stopped() || router.exhausted) return false
                    val file = files[idx]
                    val raw = store.readText(file.uri)
                    if (raw == null) {
                        log("⚠️ [W#$workerId] ファイル読込失敗のため保留します: ${file.name}（次回再試行）")
                        continue
                    }
                    val clean = cleanseBasic(raw)
                    contextTracker.putCleanSource(idx, clean)
                    if (clean.isBlank()) {
                        if (gateway.saveVerified(outputDirUri, file.name, "") != null) existing.add(file.name)
                        bump(file.name)
                        continue
                    }
                    if (utf8Bytes(clean) > splitThresholdBytes) {
                        if (!processOne(idx, raw)) return false
                        continue
                    }
                    batch.add(Member(idx, file, raw))
                    batchPrevTails.add(contextTracker.getPrevSourceTail(idx))
                }
                if (batch.isEmpty()) return true
                if (batch.size == 1) {
                    val m = batch[0]
                    return processOne(m.idx, m.raw)
                }
                log("📦 [W#$workerId] バッチ翻訳開始 (${batch.size}件): ${batch.map { it.file.name }.joinToString(", ")}")
                val outcome = translateBatch(
                    store, outputDirUri,
                    batch.map { it.file.name to cleanseBasic(it.raw) },
                    ctx,
                    prevSourceTail = batchPrevTails.firstOrNull(),
                    itemPrevTails = batchPrevTails
                )
                if (outcome.settled > 0) {
                    val done = completed.addAndGet(outcome.settled)
                    onProgress(done, total, batch.first().file.name)
                    log("✅ [W#$workerId] バッチ翻訳完了 (${outcome.savedFiles.size}件保存)")
                }
                // 保存系はstage内で完結するため、結果から既存集合を直接同期（不要なfindChild Binder IPCクエリを全廃）
                // 技術的根拠: SAFの findChild は O(N) の線形ディレクトリ走査・IPCクエリを伴うため、translateBatch の確定ファイル名で1工程同期する
                existing.addAll(outcome.savedFiles)
                if (outcome.configBlocked) {
                    log("⚠️ [W#$workerId] 設定エラーのため終了します（修正後に再実行可能）")
                    return false
                }
                return true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                log("❌ [W#$workerId] 束処理中に予期せぬ例外 (${t.javaClass.simpleName}: ${t.message})")
                return true
            }
        }

        while (true) {
            if (stopped() || router.exhausted) break
            val bundle = pool.claimNext() ?: break
            if (bundle.indices.size == 1) {
                if (!processOne(bundle.indices[0], null)) return
            } else {
                if (!processBundle(bundle.indices)) return
            }
        }
    }

    /**
     * 貯蔵I/O系の一時失敗か（cause連鎖込み）。
     * 技術的根拠1行：LLM業界標準（接続/DNS/TLS/タイムアウト/5xx/429率は再送、400/認証/課金は確定）に合わせ、I/O・メモリ枯渇・タイムアウトは保留対象とする。
     */
    private fun isTransientStorageError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is java.io.IOException) return true
            if (cur is OutOfMemoryError) return true
            if (cur is java.util.concurrent.TimeoutException) return true
            cur = cur.cause
        }
        return false
    }
}
