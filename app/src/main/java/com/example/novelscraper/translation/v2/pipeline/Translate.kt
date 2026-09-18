package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.DefaultRetryPolicy
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.FailureNotes
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.isDeterministicFailure
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.infra.AtomicFileGateway
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import kotlinx.coroutines.delay

/** 一回の試行単位（ドライバー名＋プロンプト＋入力＋呼出）。順序＝退避優先順 */
data class Attempt(
    val driverName: String,
    val prompt: String,
    val source: String,
    val call: suspend () -> LlmResult
)

sealed interface DriverOutcome {
    data class Ok(val text: String, val promptTokens: Int = 0, val completionTokens: Int = 0) : DriverOutcome
    data class GiveUp(val terminal: FailureKind, val configOnly: Boolean, val note: String = "") : DriverOutcome
    data object Stopped : DriverOutcome
}

data class AttemptOptions(
    val maxSameRetries: Int = 2,
    val retryDelayMs: (attempt: Int) -> Long = DefaultRetryPolicy::delayForAttempt,
    val stopped: () -> Boolean = { false },
    val meter: CostMeter? = null,
    val log: (String) -> Unit = {}
)

/**
 * 再試行層。同一ドライバー内の待機再送のみを行い、モデル切替の判断は順序に委ねる。
 * エンジン経路では Rotation がモデル巡回・コスト計上を担うため、同一切替なし（maxSameRetries=0）かつ
 * meter=null で使う（ここにmeterを渡すと二重計上になる）。
 */
/**
 * 試行群の集計（PolicyEngine の判定核）。運転者単位・指示文単位の両役が共有する。
 * 技術的根拠1行：終端決定の二重実装は必ず乖離するため、仕分けと決定はここに一本化する。
 */
class AttemptTally {
    private var sawConfig = false
    private var sawDeterministic = false
    private var sawTransient = false
    private var lastKind: FailureKind? = null
    private var lastNote = ""
    private var detKind: FailureKind? = null
    private var detNote: String? = null

    fun record(kind: FailureKind, note: String) {
        lastKind = kind
        lastNote = note
        when (kind) {
            FailureKind.BLOCKED_DETERMINISTIC, FailureKind.FATAL -> {
                sawDeterministic = true
                detKind = kind
                detNote = note
            }
            FailureKind.CONFIG -> sawConfig = true
            FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> sawTransient = true
        }
    }

    /** 品質不合格（検証NG）は終端種別を FATAL 扱いで記録するが、確定旗は立てない（最終述語に委ねる）。 */
    fun recordQualityReject(note: String) {
        lastKind = FailureKind.FATAL
        lastNote = note
    }

    /** 設定のみ・かつ他種別なし */
    val configOnly: Boolean get() = sawConfig && !sawDeterministic && !sawTransient

    /** 無駄打ち防止：設定・一時的失敗が出たら巡回を打ち切る */
    fun shouldBreak(): Boolean = sawConfig || sawTransient

    fun terminal(default: FailureKind): FailureKind = when {
        configOnly -> FailureKind.CONFIG
        sawDeterministic -> detKind ?: FailureKind.BLOCKED_DETERMINISTIC
        else -> lastKind ?: default
    }

    fun note(): String = if (sawDeterministic) (detNote ?: lastNote) else lastNote
}

suspend fun attemptDrivers(attempts: List<Attempt>, options: AttemptOptions = AttemptOptions()): DriverOutcome {
    val tally = AttemptTally()
    val budget = RetryBudget()
    var lastDriver: String? = null

    for (attempt in attempts) {
        if (options.stopped()) return DriverOutcome.Stopped
        if (attempt.driverName != lastDriver) {
            budget.used = 0
            lastDriver = attempt.driverName
        }
        // 技術的根拠1行：再試行の mechanics は骨格カーネルに一任し、ここでは運転者単位の予算所有と結果の仕分けだけを行う。
        when (val settled = callWithRetry(
            attempt.call, budget, options.maxSameRetries, options.retryDelayMs,
            options.stopped, options.meter, options.log
        )) {
            is CallSettled.Ok -> return DriverOutcome.Ok(
                settled.result.text, settled.result.promptTokens, settled.result.completionTokens
            )
            is CallSettled.Stopped -> return DriverOutcome.Stopped
            is CallSettled.Terminal -> tally.record(settled.failure.kind, settled.failure.note)
        }
    }
    if (options.stopped()) return DriverOutcome.Stopped
    // 技術的根拠1行：設定エラーのみならワーカー停止、確定的失敗ならその障害種別を維持（FATAL丸め込みによる判定漏れ防止）、外的要因のみなら元の障害種別を返して.failed作成を防ぐ。
    return DriverOutcome.GiveUp(tally.terminal(FailureKind.RETRYABLE_AFTER), configOnly = tally.configOnly, note = tally.note())
}

data class VerifyOptions(
    val sizeMinPct: Int = 50,
    val sizeMaxPct: Int = 300,
    val kanaFloor: Double = 0.2,
    val markerEnabled: Boolean = true,
    /** Null disables residual detection (keeps existing callers/tests unchanged). */
    val residual: ResidualOptions? = null,
    /** 非null時は辞書検査（注釈畳み＋必須語存在）を行う。方式差は言語方針が吸収する */
    val dictCheck: DictCheck? = null
)

fun verifyTranslation(sourceText: String, translatedText: String, options: VerifyOptions): String? {
    val receipt = assessCompletion(sourceText, translatedText, options)
    return if (receipt.complete) receipt.cleaned else null
}

sealed interface SingleResult {
    data class Translated(val text: String) : SingleResult
    /** terminal/note はログ表示用（.failed の中身は原文のまま） */
    data class Failed(
        val terminal: FailureKind,
        val note: String = "",
        val isDeterministic: Boolean = false
    ) : SingleResult
    data object ConfigOnly : SingleResult
    data object Stopped : SingleResult
}

/**
 * 最終推敲の設定ひとまとまり。null＝推敲なし（既定）。
 * 技術的根拠1行：on/off・指示文・送り口の3点が散ると有効条件が路ごとにずれるため、1個に束ねる。
 */
data class RefineConfig(
    /** 解決済み推敲指示文 */
    val prompt: String,
    /** 磨き専用の送信 binding（主経路から作る。巡回・待機の適用は作り手の責務） */
    val call: suspend (prompt: String, source: String) -> LlmResult
)

data class TranslateContext(
    val basePrompts: Map<Int, String>,
    val promptOrder: List<Int>,
    val driverNames: List<String>,
    val dictionary: NovelDict? = null,
    val verify: VerifyOptions = VerifyOptions(),
    /** 言語検出結果。辞書方式の選択に使う（韓・英＝対応表、他＝注釈。既定は中国語互換のためZH） */
    val sourceLang: SourceLang = SourceLang.ZH,
    /** 最終推敲の設定ひとまとまり。null＝推敲なし（既定・従来動作） */
    val refine: RefineConfig? = null,
    /** ドライバー名・構造化指示・入力を受けて送信する（巡回適用済み binding。描画は受信側の責務） */
    val call: suspend (driverName: String, spec: PromptSpec, source: String) -> LlmResult,
    /** バッチ枠専用の送信 binding（構造化出力の適用範囲をバッチに限定する）。null時はcallを使う */
    val callBatch: (suspend (driverName: String, spec: PromptSpec, source: String) -> LlmResult)? = null,
    /** バッチ枠をJSON形式で組み立てる（callBatch側のスキーマ指定と対にする） */
    val batchJsonFormat: Boolean = false,
    /** 人物メモの添付可否。偽＝辞書にメモがあっても送らない（設定連動） */
    val profileMemoEnabled: Boolean = true,
    /** 前文脈注入の設定連動（単体・バッチフォールバックで共有） */
    val prevContextLines: Int = 20,
    val prevContextEnabled: Boolean = true,
    val maxSameRetries: Int = 2,
    val stopped: () -> Boolean = { false },
    val meter: CostMeter? = null,
    val log: (String) -> Unit = {}
)

private fun buildAttempts(
    ctx: TranslateContext,
    prevTranslatedTail: String?,
    prevSourceTail: String?,
    sourceForMarker: String,
    batchFormat: String? = null,
    /** 非null時は対応確定の1行指示を付ける（本文は呼出側で注釈済み） */
    termAnnotation: TermAnnotation? = null,
    /** 非null時は人物対応表を付ける（韓国語用。注釈方式とは択一） */
    glossary: Map<String, String>? = null,
    /** 非null・非blank時は人物メモブロックを付ける（方式を問わず併用可・参考情報） */
    profileMemo: String? = null
): List<Attempt> {
    val attempts = mutableListOf<Attempt>()
    // 技術的根拠1行：構造化出力の適用範囲をバッチ枠に限定するため、枠種別で送信bindingを使い分ける
    val invoke = if (batchFormat != null) (ctx.callBatch ?: ctx.call) else ctx.call
    // 技術的根拠1行：頭差し替えを文字列手術にしないため、部品 capture の factory に寄せ、描画は受信側に委ねる。
    val factory = { promptNum: Int ->
        buildSpec(
            headNum = promptNum,
            headText = requireHeadText(ctx.basePrompts, promptNum),
            previousTranslatedTail = prevTranslatedTail,
            previousSourceTail = prevSourceTail,
            termAnnotation = termAnnotation,
            glossary = glossary,
            batchFormat = batchFormat,
            profileMemo = profileMemo,
            enableCompletionMarker = ctx.verify.markerEnabled
        )
    }
    for (driver in ctx.driverNames) {
        for (promptNum in ctx.promptOrder.ifEmpty { listOf(1, 1) }) {
            val spec = factory(promptNum)
            // 技術的根拠1行：版の特定を再生デバッグに載せるため、単品路と同一形式で短縮ハッシュを残す。
            ctx.log("spec:${promptSpecHash(spec).take(7)} #$promptNum")
            val source = appendMarker(sourceForMarker, ctx.verify.markerEnabled)
            attempts.add(Attempt(driver, assemblePrompt(spec), source) { invoke(driver, spec, source) })
        }
    }
    return attempts
}

/**
 * 最終推敲の単一入口。単品・束ねの両路が共有する。
 * 初回訳文を原文＋対応表付きで磨き直し、同一検査に通れば採用、不合格・制限・停止時は初回訳文をそのまま返す。
 * 技術的根拠1行：磨き直しの成否判定を1箇所にし、路ごとの悪化（改悪採用・無限再送）を構造的に不可能にする。
 * 動作例：初回「軍人やイナ、ベテラン」→推敲「軍人やベテラン」で採用、推敲が崩れたら初回を採用する。
 */
suspend fun polishTranslation(
    content: String,
    first: String,
    ctx: TranslateContext,
    verify: VerifyOptions
): String {
    val refine = ctx.refine
    if (refine == null || refine.prompt.isBlank() || ctx.stopped()) return first
    val terms = verify.dictCheck?.terms ?: emptyMap()
    val input = buildRefineInput(content, first, terms)
    val budget = RetryBudget()
    // 技術的根拠1行：再試行の数え方・待機・停止検査は骨格カーネルに一任し、ここでは初回採用への倒し方だけを決める（待機再送なしの1回勝負）。
    return when (val settled = callWithRetry(
        { refine.call(refine.prompt, input) },
        budget, 0, DefaultRetryPolicy::delayForAttempt,
        ctx.stopped, ctx.meter, ctx.log
    )) {
        is CallSettled.Ok -> {
            // 技術的根拠1行：磨き文に完走栞はないため栞検査だけ外し、人物・量・かな率の物差しは初回と同一にする。
            val refined = verifyTranslation(content, settled.result.text, verify.copy(markerEnabled = false))
            if (refined != null) {
                ctx.log("✨ 推敲で更新しました")
                refined
            } else {
                ctx.log("推敲結果を破棄し初回訳を採用します")
                first
            }
        }
        is CallSettled.Terminal -> first.also {
            ctx.log("推敲を素通しします (${settled.failure.kind})")
        }
        is CallSettled.Stopped -> first
    }
}

/**
 * 単体翻訳。設定されたプロンプト順序（例: [3, 7]）で試行し、品質合格時に検証済み訳文を返す。
 * 技術的根拠1行：品質チェック不合格時は次のプロンプトで再試行し、全滅時のみ確定失敗とする。
 */
suspend fun translateSingle(
    content: String,
    ctx: TranslateContext,
    prevTranslatedTail: String? = null,
    prevSourceTail: String? = null
): SingleResult {
    if (ctx.stopped()) return SingleResult.Stopped

    // 技術的根拠1行：方式選択は入口の1回だけにし、送る→確かめる→保存する骨格は言語で変えない。
    val policy = DictPolicy.forLang(ctx.sourceLang)
    // 技術的根拠1行：本文中に登場する人物のみを対象にし、該当なし時は無関係な参考例を注入せずトークン浪費とハルシネーションを防ぐ。
    val dictTerms = if (ctx.dictionary != null) {
        policy.matchTerms(content, ctx.dictionary.characters)
    } else {
        emptyMap()
    }
    val aliasTerms = if (ctx.dictionary != null) {
        policy.matchAliases(
            content, ctx.dictionary.characters,
            exclude = dictTerms.keys,
            limit = (TranslationLimits.DICT_MATCH_LIMIT - dictTerms.size).coerceAtLeast(0),
            onConflict = { c -> ctx.log("辞書別名: 読み衝突のため先勝ち ($c)") }
        )
    } else {
        emptyMap()
    }
    val allTerms = dictTerms + aliasTerms
    // 技術的根拠1行：値同一・1字見出しは注釈が騒音と誤検知にしかならないため、適用・検証の両方から外す（可否は共有述語に寄せる）。
    val activeTerms = allTerms.filter { (key, value) -> isAnnotatableTerm(key, value) }
    val prepared = policy.prepare(content, activeTerms)
    val check = policy.check(activeTerms, prepared.annotation)
    // 技術的根拠1行：メモは登場語だけに寄せ、未登場の持ち込み（トークン浪費・混同）をなくす。
    val profileMemo = if (ctx.profileMemoEnabled && ctx.dictionary != null) {
        buildProfileMemoBlock(profileMemoTerms(activeTerms, ctx.dictionary.profiles))
    } else ""
    // 技術的根拠1行：適用人数と方式を可視化し、辞書未使用（0名・衝突）と指示無視を切り分けられるようにする。
    if (ctx.dictionary != null) {
        val aliasNote = if (aliasTerms.isNotEmpty()) "＋別名${aliasTerms.size}件" else ""
        if (prepared.annotation != null) {
            ctx.log("辞書添付: ${activeTerms.size}名（登録${ctx.dictionary.characters.size}名中・${policy.logLabel()}$aliasNote）")
        } else if (prepared.glossary != null) {
            ctx.log("辞書添付: ${activeTerms.size}名（登録${ctx.dictionary.characters.size}名中・${policy.logLabel()}）")
        } else if (activeTerms.isNotEmpty()) {
            ctx.log("辞書添付: ${activeTerms.size}名（登録${ctx.dictionary.characters.size}名中・区切り衝突のため辞書なし）")
        } else {
            ctx.log("辞書添付: 0名（登録${ctx.dictionary.characters.size}名中・なし）")
        }
    }
    // 技術的根拠1行：剥離条件は送信条件と同一にし、畳み忘れ・畳み過ぎの乖離をなくす。
    val verify = ctx.verify.copy(dictCheck = check)

    val source = appendMarker(prepared.sendText, verify.markerEnabled)
    val drivers = ctx.driverNames.ifEmpty { listOf("default") }
    val promptOrder = ctx.promptOrder.ifEmpty { listOf(1, 1) }

    val tally = AttemptTally()

    // 技術的根拠1行：頭差し替えを文字列手術にしないため、部品 capture の factory に寄せ、描画は受信側に委ねる。
    val specFactory = { promptNum: Int ->
        buildSpec(
            headNum = promptNum,
            headText = requireHeadText(ctx.basePrompts, promptNum),
            previousTranslatedTail = prevTranslatedTail,
            previousSourceTail = prevSourceTail,
            termAnnotation = prepared.annotation,
            glossary = prepared.glossary,
            batchFormat = null,
            profileMemo = profileMemo,
            enableCompletionMarker = verify.markerEnabled
        )
    }

    for (driver in drivers) {
        for ((promptIdx, promptNum) in promptOrder.withIndex()) {
            if (ctx.stopped()) return SingleResult.Stopped

            val spec = specFactory(promptNum)
            // 技術的根拠1行：版の特定を再生デバッグに載せるため、試行ごとに短縮ハッシュを残す。
            ctx.log("spec:${promptSpecHash(spec).take(7)} #$promptNum")

            val budget = RetryBudget()
            // 技術的根拠1行：再試行の mechanics は骨格カーネルに一任し、ここでは指示文単位の予算所有と品質検査の合否仕分けだけを行う。
            when (val settled = callWithRetry(
                { ctx.call(driver, spec, source) },
                budget, ctx.maxSameRetries, DefaultRetryPolicy::delayForAttempt,
                ctx.stopped, ctx.meter, ctx.log
            )) {
                is CallSettled.Ok -> {
                    // 品質チェック
                    val verified = verifyTranslation(content, settled.result.text, verify)
                    if (verified != null) {
                        if (promptIdx > 0) {
                            ctx.log("✨ プロンプト#$promptNum でのリトライに成功しました")
                        }
                        return SingleResult.Translated(polishTranslation(content, verified, ctx, verify))
                    }

                    // 品質チェック不合格
                    val rejectReason = verifyRejectReason(content, settled.result.text, verify)
                        ?: FailureNotes.VERIFY_REJECTED
                    ctx.log("⚠️ 品質チェック不合格 ($rejectReason): プロンプト#$promptNum (${promptIdx + 1}/${promptOrder.size})")
                    tally.recordQualityReject(rejectReason)
                    // 次のプロンプトへ進む
                }
                is CallSettled.Stopped -> return SingleResult.Stopped
                is CallSettled.Terminal -> tally.record(settled.failure.kind, settled.failure.note)
            }
            if (tally.shouldBreak()) break
        }
        if (tally.shouldBreak()) break
    }

    if (ctx.stopped()) return SingleResult.Stopped
    if (tally.configOnly) return SingleResult.ConfigOnly

    val terminal = tally.terminal(FailureKind.FATAL)
    val finalNote = tally.note()
    val isDeterministic = isDeterministicFailure(terminal, finalNote)
    return SingleResult.Failed(terminal, finalNote, isDeterministic = isDeterministic)
}

data class BatchOutcome(
    val completed: Int,
    val settled: Int,
    val configBlocked: Boolean = false,
    val savedFiles: List<String> = emptyList()
)

/**
 * バッチ翻訳。成功分は即保存し、欠落・検証NG・スワップ疑い分は単体へ落とす。
 *
 * 【文脈注入の仕様】:
 * 1. 一括送信時: バッチ先頭ファイルに対する直前話（i - 1）の【原文末尾（設定行数）】を prevSourceTail として注入。
 * 2. 単体フォールバック時: 各話 i について、単体翻訳と同一の直前話（i-1）の【原文末尾（設定行数・有効無視なし）】を注入（resolveBatchItems）。
 *    （前話の誤訳を後続話へ伝染させないため、訳文ではなく単体翻訳と同一の原文末尾に統一）
 *
 * 【構造化出力】: ctx.batchJsonFormat が真のとき枠指示をJSON形式にし、callBatch 経由でスキーマ付き送信する。
 */
suspend fun translateBatch(
    store: FileStore,
    outputDirUri: String,
    items: List<Pair<String, String>>,
    ctx: TranslateContext,
    prevSourceTail: String? = null,
    itemPrevTails: List<String?>? = null
): BatchOutcome {
    if (items.isEmpty() || ctx.stopped()) return BatchOutcome(0, 0)
    // 技術的根拠1行：方式選択は入口の1回だけにし、束ね・単品で数え方を変えない。
    val policy = DictPolicy.forLang(ctx.sourceLang)
    // 技術的根拠1行：束ね全体で1組の区切りを選び各話に注釈する（合成文に無ければ各話にも無いため1回の判定で足りる）。
    val dictChars = ctx.dictionary?.characters ?: emptyMap()
    val itemTerms = items.map { policy.matchTerms(it.second, dictChars) }
    val itemAliases = items.mapIndexed { i, item ->
        if (ctx.dictionary == null) emptyMap()
        else policy.matchAliases(
            item.second, dictChars,
            exclude = itemTerms[i].keys,
            limit = (TranslationLimits.DICT_MATCH_LIMIT - itemTerms[i].size).coerceAtLeast(0)
        )
    }
    val combinedTerms = itemTerms.mapIndexed { i, terms ->
        (terms + itemAliases[i]).filter { (key, value) -> isAnnotatableTerm(key, value) }
    }
    val batchPrepared = policy.prepare(
        items.joinToString("\n") { it.second },
        combinedTerms.fold(LinkedHashMap<String, String>()) { acc, m ->
            for ((k, v) in m) if (!acc.containsKey(k)) acc[k] = v
            acc
        }
    )
    // 注釈不可時（区切り衝突時）は辞書なしで素通しする。
    val sendItems = if (batchPrepared.annotation != null) {
        items.mapIndexed { i, item -> item.first to annotateSourceTerms(item.second, combinedTerms[i], batchPrepared.annotation) }
    } else items
    if (ctx.dictionary != null) {
        val attached = combinedTerms.sumOf { it.size }
        val aliasTotal = itemAliases.sumOf { it.size }
        val aliasNote = if (aliasTotal > 0) "＋別名${aliasTotal}件" else ""
        if (batchPrepared.annotation != null) {
            ctx.log("辞書添付: ${attached}名（登録${dictChars.size}名中・${policy.logLabel()}$aliasNote）")
        } else if (batchPrepared.glossary != null) {
            ctx.log("辞書添付: ${attached}名（登録${dictChars.size}名中・${policy.logLabel()}）")
        } else if (attached > 0) {
            ctx.log("辞書添付: ${attached}名（登録${dictChars.size}名中・区切り衝突のため辞書なし）")
        } else {
            ctx.log("辞書添付: 0名（登録${dictChars.size}名中・なし）")
        }
    }
    // 技術的根拠1行：メモは束ね登場語だけに寄せ、未登場の持ち込み（トークン浪費・混同）をなくす。
    val batchMemo = if (ctx.profileMemoEnabled && ctx.dictionary != null) {
        buildProfileMemoBlock(
            profileMemoTerms(
                combinedTerms.fold(LinkedHashMap<String, String>()) { acc, m ->
                    for ((k, v) in m) if (!acc.containsKey(k)) acc[k] = v
                    acc
                },
                ctx.dictionary.profiles
            )
        )
    } else ""
    val combined = buildBatchInput(sendItems)
    val batchCtx = ctx.copy(verify = ctx.verify.copy(markerEnabled = false))
    val batchFormat = if (ctx.batchJsonFormat) buildBatchJsonFormat(items.size) else buildBatchFormat(items.size)
    val attempts = buildAttempts(
        batchCtx,
        prevTranslatedTail = null, prevSourceTail = prevSourceTail, combined,
        batchFormat = batchFormat,
        termAnnotation = batchPrepared.annotation,
        glossary = batchPrepared.glossary,
        profileMemo = batchMemo
    )
    val outcome = attemptDrivers(
        attempts,
        AttemptOptions(maxSameRetries = ctx.maxSameRetries, stopped = ctx.stopped, meter = ctx.meter, log = ctx.log)
    )
    val parsed = if (outcome is DriverOutcome.Ok) parseBatchResponse(outcome.text) else null
    if (parsed == null) {
        if (outcome is DriverOutcome.GiveUp && outcome.configOnly) return BatchOutcome(0, 0, configBlocked = true)
        if (ctx.stopped()) return BatchOutcome(0, 0)
        // 技術的根拠1行：クォータ枯渇・通信障害等の外的要因全滅時は単体に落としても100%失敗するため、無駄な多重送信（N倍の通信・待機時間浪費）を防止し即時保留する。
        if (outcome is DriverOutcome.GiveUp && outcome.terminal.isQuotaLike()) {
            ctx.log("バッチ全滅 (外的要因: ${outcome.terminal}) のため単体フォールバックをスキップし未完了保留とします")
            return BatchOutcome(0, 0)
        }
        // 出力形式崩れや確定障害時は単体フォールバックで救済を試みる
        return resolveBatchItems(store, outputDirUri, items, ctx, prevSourceTail, initialDone = null, itemPrevTails = itemPrevTails)
    }

    // スワップ（テレコ）検知ガード：疑わしい場合は安全のため単体フォールバックへ
    if (detectBatchSwap(items, parsed)) {
        ctx.log("batch swap detected, falling back to singles")
        return resolveBatchItems(store, outputDirUri, items, ctx, prevSourceTail, initialDone = null, itemPrevTails = itemPrevTails)
    }

    var completed = 0
    var settled = 0
    val done = BooleanArray(items.size) { false }
    val saved = mutableListOf<String>()
    // 技術的根拠1行：欠けの判定は共有検証口に一任し、保存路と再送路で数え方を変えない。
    // 技術的根拠1行：剥離条件は送信条件と同一にし、畳み忘れ・畳み過ぎの乖離をなくす。
    val itemChecks = combinedTerms.map { terms ->
        policy.check(terms, batchPrepared.annotation)
    }
    val validation = validateBatchCompleteness(items, parsed, ctx.verify, itemChecks)
    if (validation.missingIds.isNotEmpty()) {
        ctx.log("batch incomplete ids (単品再送へ): ${validation.missingIds.joinToString(",")}")
    }

    for ((idx, item) in items.withIndex()) {
        if (ctx.stopped()) break
        val (fileName, _) = item
        val verified = validation.verified[idx] ?: continue
        // 技術的根拠1行：磨き直しは単品路と同一口に寄せ、束ね独自の再送・保存手順を作らない。
        val vo = validation.vos[idx] ?: ctx.verify.copy(markerEnabled = false)
        val final = polishTranslation(item.second, verified, ctx, vo)
        if (saveOutputText(store, outputDirUri, fileName, final, log = ctx.log) != null) {
            completed++
            settled++
            done[idx] = true
            saved.add(fileName)
            ctx.log("batch saved: $fileName")
        }
    }

    // 欠落・検証NG分をファイル順（インデックス順）に単体フォールバック
    if (done.any { !it } && !ctx.stopped()) {
        val fb = resolveBatchItems(
            store, outputDirUri, items, ctx,
            prevSourceTail,
            initialDone = done,
            itemPrevTails = itemPrevTails
        )
        completed += fb.completed
        settled += fb.settled
        saved.addAll(fb.savedFiles)
        if (fb.configBlocked) return BatchOutcome(completed, settled, configBlocked = true, savedFiles = saved)
    }
    return BatchOutcome(completed, settled, savedFiles = saved)
}

/**
 * バッチ内のアイテムをインデックス順（0..N-1）に処理・補完する。
 * 各話 i のフォールバック実行時、単体翻訳と同一の「直前話（i-1）の原文末尾（設定行数・有効連動）」を渡す（誤訳連鎖を防止）。
 * itemPrevTails（呼出側の全体文脈から算出済み）があればそれを優先し、なければバッチ内直前話から設定行数で切り出す。
 */
private suspend fun resolveBatchItems(
    store: FileStore,
    outputDirUri: String,
    items: List<Pair<String, String>>,
    ctx: TranslateContext,
    batchPrevSourceTail: String?,
    initialDone: BooleanArray?,
    itemPrevTails: List<String?>? = null
): BatchOutcome {
    var completed = 0
    var settled = 0
    val done = initialDone ?: BooleanArray(items.size) { false }
    val saved = mutableListOf<String>()

    for (i in items.indices) {
        if (ctx.stopped()) break
        if (done[i]) continue

        val (fileName, content) = items[i]

        // 直前話（i - 1）の原文末尾を特定（単体翻訳と同一の仕様。設定の有効・行数に連動）
        val itemPrevSrcTail = itemPrevTails?.getOrNull(i) ?: if (i == 0) {
            batchPrevSourceTail
        } else if (!ctx.prevContextEnabled) {
            null
        } else {
            items[i - 1].second.lines().takeLast(ctx.prevContextLines.coerceIn(1, 100)).joinToString("\n")
        }

        when (val r = translateSingle(content, ctx, prevTranslatedTail = null, prevSourceTail = itemPrevSrcTail)) {
            is SingleResult.Translated -> {
                if (saveOutputText(store, outputDirUri, fileName, r.text, log = ctx.log) != null) {
                    completed++
                    done[i] = true
                    saved.add(fileName)
                }
                settled++
            }
            is SingleResult.Failed -> {
                if (shouldPersistFailed(r)) {
                    if (writeFailed(store, outputDirUri, fileName, content, ctx.log)) {
                        saved.add("$fileName.failed")
                    }
                    settled++
                } else {
                    ctx.log("⚠️ 一時エラー/制限のため未完了として保留します: $fileName (${r.terminal} ${r.note})".trim())
                }
            }
            is SingleResult.ConfigOnly -> return BatchOutcome(completed, settled, configBlocked = true, savedFiles = saved)
            is SingleResult.Stopped -> break
        }
    }
    return BatchOutcome(completed, settled, savedFiles = saved)
}

/**
 * 重複 "(1)" を作らない生成。実体は [AtomicFileGateway]。
 */
internal suspend fun findOrCreateFile(
    store: FileStore,
    dirUri: String,
    name: String,
    mime: String
): VDoc? = AtomicFileGateway(store).findOrCreateFile(dirUri, name, mime)

/**
 * 骨格カーネル（単一パイプラインの不変部）。
 * 単体・束ね・大ファイルの3役が「送る→確かめる→保存する」の共通部として共有する。
 * 可変部（依頼文の成形・応答の解釈・文脈の繋ぎ方）は各役に残す（Template Method の考え方）。
 * 技術的根拠1行：不変部を1実装にすることで再試行回数・保存手順・確定方針の乖離を構造的に不可能にする。
 */

/** 再試行予算。所有した側の有効範囲（運転者単位／指示文単位）で回数を数える。 */
class RetryBudget {
    var used: Int = 0
}

/** 1回の送信（待機再送込み）の確定結果。 */
sealed interface CallSettled {
    data class Ok(val result: LlmResult.Success) : CallSettled
    data class Terminal(val failure: ClassifiedFailure) : CallSettled
    data object Stopped : CallSettled
}

/**
 * 唯一の再試行実装。確定的・設定失敗は即確定し、一時的失敗（制限・混雑）は予算内で待機再送する。
 * 成功時のコスト上限検査もここで行い、上限到達は停止として返す。
 * 技術的根拠1行：待機回数の数え方・待機時間式・停止検査を1箇所にし、運転者単位と指示文単位の違いは予算の所有側だけで表現する。
 */
suspend fun callWithRetry(
    call: suspend () -> LlmResult,
    budget: RetryBudget,
    maxSameRetries: Int,
    retryDelayMs: (attempt: Int) -> Long,
    stopped: () -> Boolean,
    meter: CostMeter?,
    log: (String) -> Unit
): CallSettled {
    while (true) {
        if (stopped()) return CallSettled.Stopped
        when (val result = call()) {
            is LlmResult.Success -> {
                if (meter != null && !meter.add(
                        tokens = (result.promptTokens + result.completionTokens).toLong(),
                        cost = 0.0
                    )
                ) {
                    log("cost cap reached, halt")
                    return CallSettled.Stopped
                }
                return CallSettled.Ok(result)
            }
            is LlmResult.Failure -> when (result.failure.kind) {
                FailureKind.BLOCKED_DETERMINISTIC, FailureKind.FATAL, FailureKind.CONFIG ->
                    return CallSettled.Terminal(result.failure)
                FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> {
                    if (budget.used < maxSameRetries) {
                        budget.used++
                        delay(retryDelayMs(budget.used))
                        continue
                    }
                    return CallSettled.Terminal(result.failure)
                }
            }
        }
    }
}

/**
 * 訳文保存口。実体は [AtomicFileGateway.saveVerified]。
 */
suspend fun saveOutputText(
    store: FileStore,
    dirUri: String,
    fileName: String,
    text: String,
    mime: String = "text/plain",
    existing: VDoc? = null,
    log: (String) -> Unit = {}
): VDoc? = AtomicFileGateway(store, log).saveVerified(dirUri, fileName, text, mime, existing)

/** 最善努力の削除。実体は [AtomicFileGateway]。 */
internal suspend fun deleteQuietly(store: FileStore, fileUri: String, log: (String) -> Unit) {
    AtomicFileGateway(store, log).deleteQuietly(fileUri)
}

/**
 * 書込内容の照合。実体は [AtomicFileGateway]。
 */
suspend fun verifyBytes(
    store: FileStore,
    fileUri: String,
    fileName: String,
    text: String,
    log: (String) -> Unit = {}
): Boolean = AtomicFileGateway(store, log).verifyBytes(fileUri, fileName, text)

/**
 * 唯一の失敗確定方針。内容依存の確定的失敗だけ `.failed` 確定し、
 * 回線・制限等の一時的失敗は未完了保留（再実行で救済）にする。
 * 技術的根拠1行：失敗内容による扱い分けを1述語にし、単体・束ね・大ファイルでの判定乖離をなくす。
 */
fun shouldPersistFailed(failed: SingleResult.Failed): Boolean =
    failed.isDeterministic || isDeterministicFailure(failed.terminal, failed.note)

suspend fun writeFailed(
    store: FileStore,
    outputDirUri: String,
    fileName: String,
    content: String,
    log: (String) -> Unit = {}
): Boolean {
    val failedName = "$fileName.failed"
    val doc = findOrCreateFile(store, outputDirUri, failedName, "application/octet-stream")
    if (doc != null && store.writeText(doc.uri, content)) {
        log("failed file saved: $failedName")
        return true
    }
    log("failed file save error: $failedName")
    return false
}

data class LargeOptions(
    val chunkSizeBytes: Int = 30000,
    val tailLines: Int = 20,
    /** 上限（これを超えたら保持で返し、呼出側は見送り扱いにする） */
    val maxInputBytes: Int = 10_000_000
)

/**
 * 大ファイル翻訳の結果。記録（ログ）のために理由と進捗を持つ。
 * 技術的根拠1行：真偽値では「何が・なぜ・どこまで」が残らず原因追跡不能になるため、結果を理由付きで返す。
 */
sealed interface LargeOutcome {
    data object Completed : LargeOutcome
    data class Held(val reason: String, val completedChunks: Int, val totalChunks: Int) : LargeOutcome
    /** 終端：同一記録で未解決停止が続き、再開成功が構造的にあり得ない。呼出側は親失敗記録に回す。 */
    data class Dead(val reason: String, val completedChunks: Int, val totalChunks: Int) : LargeOutcome
    data object Stopped : LargeOutcome
}

/**
 * 大ファイル翻訳（チャンク翻訳）。
 * 原文・分割・結合文をメモリに保持して処理する。結合はメモリに組み立てて一括保存する（追記はしない）。
 * 前提：workDirUri は呼出側が用意する（成功時に消えるため、完成済みの再呼出しはしないこと）。
 * 読込・解決不能の保持には終端カウンタを付けない（一時的扱いとし、次回再試行に委ねる）。
 *
 * 【文脈注入の仕様】:
 * 1. 先頭の未処理チャンク: 前の話（前ファイル）の【原文末尾（呼出側の設定行数）】（prevSourceTail）を注入。
 * 2. 後続チャンク: 同一エピソード内の文脈接続を維持するため、
 *    直前チャンクの【翻訳後の確定訳文末尾（tailLines行）】（prevTranslatedTail）を数珠つなぎで注入。
 *    （訳文末尾と原文末尾の重ね注入はしない。buildSpec側で単一化する）
 */
suspend fun translateLarge(
    store: FileStore,
    workDirUri: String,
    outputDirUri: String,
    fileName: String,
    content: String,
    ctx: TranslateContext,
    options: LargeOptions = LargeOptions(),
    prevSourceTail: String? = null,
    onChunkProgress: ((current: Int, total: Int) -> Unit)? = null
): LargeOutcome {
    if (ctx.stopped()) return LargeOutcome.Stopped
    if (utf8Bytes(content) > options.maxInputBytes) {
        ctx.log("large: over input cap, skip")
        return LargeOutcome.Held("上限超過のためスキップ", 0, 0)
    }
    // 技術的根拠1行：宣言書で正本化された作業だけを扱い、欠けた入塊・設定変更・原文編集の残骸は作り直して欠落結合を起こさない。
    val session = prepareChunkSession(store, workDirUri, content, options.chunkSizeBytes, ctx.log)
        ?: return LargeOutcome.Held("作業の用意に失敗（詳細は直前のログ）", 0, 0)
    val chunkNames = session.names
    var manifest = session.manifest
    val outDirUri = session.outDirUri

    // 入塊は内容解決済み（名寄せ不要）。出側は完了台帳つきで名簿対応分だけ使う。
    // 技術的根拠1行：一覧の表示名は改変され得るため、入塊はハッシュ解決済み文書、出側は台帳記録名で追跡する。
    val inDocs = session.inputs.toMutableMap()
    val scanned = store.children(outDirUri).associateBy { it.name }
    val outDocs = mutableMapOf<String, VDoc>()
    for (entry in manifest.chunks) {
        val recorded = manifest.done[entry.name]
        val doc = (recorded?.let { scanned[it] } ?: scanned[entry.name])?.takeIf { !it.isDirectory }
        if (doc != null) outDocs[entry.name] = doc
        scanned[entry.name + ".failed"]?.takeIf { !it.isDirectory }?.let { outDocs[entry.name + ".failed"] = it }
    }

    var prevTail: String? = null
    // Resume: rebuild the context from finished chunks.
    // The raw-source tail is injected into the first chunk only (never stacked).
    var firstPending = true
    for ((chunkIdx, name) in chunkNames.withIndex()) {
        if (ctx.stopped()) return LargeOutcome.Stopped
        onChunkProgress?.invoke(chunkIdx + 1, chunkNames.size)

        // 技術的根拠: $name.failed の二重クエリ・デッドフォールバックを解消し、未解決失敗があれば即座に中断
        if (outDocs.containsKey("$name.failed")) {
            ctx.log("large: unresolved $name.failed, halt")
            // 技術的根拠1行：同一記録での連続停止を数え、上限で終端する。送らず止まり続ける無限保持をなくす。
            manifest = noteHaltStreak(store, workDirUri, manifest, name, ctx.log)
            if (manifest.haltStreak >= DEAD_HALT_STREAK) {
                return LargeOutcome.Dead("未解決の確定失敗が続くため終端（$name）", chunkIdx, chunkNames.size)
            }
            return LargeOutcome.Held("未解決の確定失敗あり（$name）", chunkIdx, chunkNames.size)
        }

        val existing = outDocs[name]
        val saved = existing?.let { store.readText(it.uri) }
        if (!saved.isNullOrBlank()) {
            prevTail = saved.lines().takeLast(options.tailLines).joinToString("\n")
            firstPending = false
            continue
        }

        val chunkDoc = inDocs[name]
            ?: return LargeOutcome.Held("入力塊の解決に失敗（$name）", chunkIdx, chunkNames.size)
        val chunkText = store.readText(chunkDoc.uri)
            ?: return LargeOutcome.Held("入力塊の読込に失敗（$name）", chunkIdx, chunkNames.size)
        val srcTail = if (firstPending) prevSourceTail else null
        firstPending = false
        when (val r = translateSingle(chunkText, ctx, prevTranslatedTail = prevTail, prevSourceTail = srcTail)) {
            is SingleResult.Translated -> {
                val out = saveOutputText(store, outDirUri, name, r.text, existing = outDocs[name], log = ctx.log)
                    ?: return LargeOutcome.Held("訳文の保存に失敗（$name）", chunkIdx, chunkNames.size)
                outDocs[name] = out // 動的同期: 新規作成・更新チャンクをキャッシュに即時反映
                // 技術的根拠1行：表示名改変がある場合だけ完了台帳に実名を追記し、次回の再利用を保つ。
                if (!out.name.equals(name, ignoreCase = true)) {
                    manifest = markChunkDone(store, workDirUri, manifest, name, out.name, ctx.log)
                }
                prevTail = r.text.lines().takeLast(options.tailLines).joinToString("\n")
            }
            is SingleResult.Failed -> {
                ctx.log("large: chunk $name failed (${r.terminal} ${r.note})".trim())
                if (shouldPersistFailed(r)) {
                    writeFailed(store, outDirUri, name, chunkText, ctx.log)
                    store.findChild(outDirUri, "$name.failed")?.let { outDocs["$name.failed"] = it }
                    return LargeOutcome.Held("内容依存の確定失敗（${r.terminal}）。記録済み", chunkIdx, chunkNames.size)
                }
                return LargeOutcome.Held("一時的失敗（${r.terminal} ${r.note}）。次回再開".trim(), chunkIdx, chunkNames.size)
            }
            is SingleResult.ConfigOnly -> return LargeOutcome.Held("設定エラーのため中断", chunkIdx, chunkNames.size)
            is SingleResult.Stopped -> return LargeOutcome.Stopped
        }
    }

    if (ctx.stopped()) return LargeOutcome.Stopped
    // 技術的根拠1行：最終公開も共有保存口（別名→照合→確定）で行い、大ファイルだけが非確定公開にならないようにする。
    val joined = buildJoinedText(store, outDirUri, chunkNames, ctx.log, outDocs = outDocs)
        ?: return LargeOutcome.Held("結合に失敗（欠落・空文あり）", chunkNames.size, chunkNames.size)
    if (saveOutputText(store, outputDirUri, fileName, joined, log = ctx.log) == null) {
        return LargeOutcome.Held("最終成果物の確定に失敗", chunkNames.size, chunkNames.size)
    }
    store.deleteRecursively(workDirUri)
    return LargeOutcome.Completed
}
