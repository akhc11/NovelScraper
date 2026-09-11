package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.isDeterministicFailure
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** SAFでの同名衝突による自動付与 " (1)" を検知する正規表現（呼出毎コンパイル回避） */
private val COLLISION_RENAME_REGEX = Regex(""".*\s\(\d+\).*""")

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
    val retryDelayMs: (attempt: Int) -> Long = { attempt -> 1000L * (attempt + 1) },
    val stopped: () -> Boolean = { false },
    val meter: CostMeter? = null,
    val log: (String) -> Unit = {}
)

/**
 * 再試行層。同一ドライバー内の待機再送のみを行い、モデル切替の判断は順序に委ねる。
 * エンジン経路では Rotation がモデル巡回・コスト計上を担うため、同一切替なし（maxSameRetries=0）かつ
 * meter=null で使う（ここにmeterを渡すと二重計上になる）。
 */
suspend fun attemptDrivers(attempts: List<Attempt>, options: AttemptOptions = AttemptOptions()): DriverOutcome {
    var sawConfig = false
    var sawDeterministic = false
    var sawTransient = false
    var lastFailureKind: FailureKind? = null
    val budget = RetryBudget()
    var lastDriver: String? = null
    var lastNote = ""

    var lastDeterministicKind: FailureKind? = null
    var lastDeterministicNote: String? = null

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
            is CallSettled.Terminal -> {
                lastNote = settled.failure.note
                lastFailureKind = settled.failure.kind
                when (settled.failure.kind) {
                    FailureKind.BLOCKED_DETERMINISTIC, FailureKind.FATAL -> {
                        sawDeterministic = true
                        lastDeterministicKind = settled.failure.kind
                        lastDeterministicNote = settled.failure.note
                    }
                    FailureKind.CONFIG -> sawConfig = true
                    FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> {
                        sawTransient = true
                    }
                }
            }
        }
    }
    if (options.stopped()) return DriverOutcome.Stopped
    // 技術的根拠1行：設定エラーのみならワーカー停止、確定的失敗ならその障害種別を維持（FATAL丸め込みによる判定漏れ防止）、外的要因のみなら元の障害種別を返して.failed作成を防ぐ。
    val terminal = when {
        sawConfig && !sawDeterministic && !sawTransient -> FailureKind.CONFIG
        sawDeterministic -> lastDeterministicKind ?: FailureKind.BLOCKED_DETERMINISTIC
        else -> lastFailureKind ?: FailureKind.RETRYABLE_AFTER
    }
    val configOnly = sawConfig && !sawDeterministic && !sawTransient
    val note = if (sawDeterministic) (lastDeterministicNote ?: lastNote) else lastNote
    return DriverOutcome.GiveUp(terminal, configOnly = configOnly, note = note)
}

data class VerifyOptions(
    val sizeMinPct: Int = 50,
    val sizeMaxPct: Int = 300,
    val kanaFloor: Double = 0.2,
    val markerEnabled: Boolean = true,
    /** Null disables residual detection (keeps existing callers/tests unchanged). */
    val residual: ResidualOptions? = null
)

fun verifyTranslation(sourceText: String, translatedText: String, options: VerifyOptions): String? {
    val stripped = stripFences(translatedText)
    val withoutMarker = checkAndStripMarker(stripped, options.markerEnabled) ?: return null
    val cleaned = withoutMarker.trim()
    if (cleaned.isBlank()) return null
    if (options.residual != null && residualFailure(cleaned, options.residual) != null) return null
    if (!lineCountOk(sourceText, cleaned)) return null
    if (!sizeRatioOk(sourceText, cleaned, options.sizeMinPct, options.sizeMaxPct)) return null
    if (!meetsKanaFloor(cleaned, options.kanaFloor)) return null
    return cleaned
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

data class TranslateContext(
    val basePrompts: Map<Int, String>,
    val promptOrder: List<Int>,
    val driverNames: List<String>,
    val dictionary: NovelDict? = null,
    val dictionaryStyle: String? = null,
    val verify: VerifyOptions = VerifyOptions(),
    /** ドライバー名・プロンプト・入力を受けて送信する（巡回適用済み binding） */
    val call: suspend (driverName: String, prompt: String, source: String) -> LlmResult,
    /** バッチ枠専用の送信 binding（構造化出力の適用範囲をバッチに限定する）。null時はcallを使う */
    val callBatch: (suspend (driverName: String, prompt: String, source: String) -> LlmResult)? = null,
    /** バッチ枠をJSON形式で組み立てる（callBatch側のスキーマ指定と対にする） */
    val batchJsonFormat: Boolean = false,
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
    chunkText: String,
    prevTranslatedTail: String?,
    prevSourceTail: String?,
    sourceForMarker: String,
    batchFormat: String? = null
): List<Attempt> {
    val attempts = mutableListOf<Attempt>()
    // 技術的根拠1行：構造化出力の適用範囲をバッチ枠に限定するため、枠種別で送信bindingを使い分ける
    val invoke = if (batchFormat != null) (ctx.callBatch ?: ctx.call) else ctx.call
    // 技術的根拠1行：本文中に登場する人物のみを注入し、該当なし時は無関係な参考例を注入せずトークン浪費とハルシネーションを防ぐ
    val dictEntries = if (ctx.dictionary != null) {
        matchDictionaryEntries(chunkText, ctx.dictionary.characters, ctx.dictionary.genders)
    } else {
        emptyList()
    }
    for (driver in ctx.driverNames) {
        for (promptNum in ctx.promptOrder.ifEmpty { listOf(1, 1) }) {
            val base = ctx.basePrompts[promptNum] ?: ctx.basePrompts.values.firstOrNull() ?: ""
            val prompt = buildSystemPrompt(
                basePrompt = base,
                previousTranslatedTail = prevTranslatedTail,
                previousSourceTail = prevSourceTail,
                dictionaryEntries = dictEntries,
                dictionaryStyle = ctx.dictionaryStyle,
                enableCompletionMarker = ctx.verify.markerEnabled,
                batchFormat = batchFormat
            )
            val source = appendMarker(sourceForMarker, ctx.verify.markerEnabled)
            attempts.add(Attempt(driver, prompt, source) { invoke(driver, prompt, source) })
        }
    }
    return attempts
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

    // 技術的根拠1行：本文中に登場する人物のみを注入し、該当なし時は無関係な参考例を注入せずトークン浪費とハルシネーションを防ぐ
    val dictEntries = if (ctx.dictionary != null) {
        matchDictionaryEntries(content, ctx.dictionary.characters, ctx.dictionary.genders)
    } else {
        emptyList()
    }

    val source = appendMarker(content, ctx.verify.markerEnabled)
    val drivers = ctx.driverNames.ifEmpty { listOf("default") }
    val promptOrder = ctx.promptOrder.ifEmpty { listOf(1, 1) }

    var lastFailureKind: FailureKind? = null
    var lastNote = ""
    var lastDeterministicKind: FailureKind? = null
    var lastDeterministicNote: String? = null
    var sawConfig = false
    var sawDeterministic = false
    var sawTransient = false

    for (driver in drivers) {
        for ((promptIdx, promptNum) in promptOrder.withIndex()) {
            if (ctx.stopped()) return SingleResult.Stopped

            val base = ctx.basePrompts[promptNum] ?: ctx.basePrompts.values.firstOrNull() ?: ""
            val prompt = buildSystemPrompt(
                basePrompt = base,
                previousTranslatedTail = prevTranslatedTail,
                previousSourceTail = prevSourceTail,
                dictionaryEntries = dictEntries,
                dictionaryStyle = ctx.dictionaryStyle,
                enableCompletionMarker = ctx.verify.markerEnabled,
                batchFormat = null
            )

            val budget = RetryBudget()
            // 技術的根拠1行：再試行の mechanics は骨格カーネルに一任し、ここでは指示文単位の予算所有と品質検査の合否仕分けだけを行う。
            // 技術的根拠: 第1引数に promptNum.toString() を渡し、bindCall側で試行中のプロンプト番号を正しく識別可能にする
            when (val settled = callWithRetry(
                { ctx.call(promptNum.toString(), prompt, source) },
                budget, ctx.maxSameRetries, { attempt -> 1000L * attempt },
                ctx.stopped, ctx.meter, ctx.log
            )) {
                is CallSettled.Ok -> {
                    // 品質チェック
                    val verified = verifyTranslation(content, settled.result.text, ctx.verify)
                    if (verified != null) {
                        if (promptIdx > 0) {
                            ctx.log("✨ プロンプト#$promptNum でのリトライに成功しました")
                        }
                        return SingleResult.Translated(verified)
                    }

                    // 品質チェック不合格
                    val rejectReason = verifyRejectReason(content, settled.result.text, ctx.verify) ?: "verify-rejected"
                    ctx.log("⚠️ 品質チェック不合格 ($rejectReason): プロンプト#$promptNum (${promptIdx + 1}/${promptOrder.size})")
                    lastFailureKind = FailureKind.FATAL
                    lastNote = rejectReason
                    // 次のプロンプトへ進む
                }
                is CallSettled.Stopped -> return SingleResult.Stopped
                is CallSettled.Terminal -> {
                    lastNote = settled.failure.note
                    lastFailureKind = settled.failure.kind
                    when (settled.failure.kind) {
                        FailureKind.BLOCKED_DETERMINISTIC, FailureKind.FATAL -> {
                            sawDeterministic = true
                            lastDeterministicKind = settled.failure.kind
                            lastDeterministicNote = settled.failure.note
                        }
                        FailureKind.CONFIG -> sawConfig = true
                        FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> {
                            sawTransient = true
                        }
                    }
                }
            }
            if (sawConfig || sawTransient) break
        }
        if (sawConfig || sawTransient) break
    }

    if (ctx.stopped()) return SingleResult.Stopped
    if (sawConfig && !sawDeterministic && !sawTransient) return SingleResult.ConfigOnly

    val terminal = when {
        sawDeterministic -> lastDeterministicKind ?: FailureKind.BLOCKED_DETERMINISTIC
        else -> lastFailureKind ?: FailureKind.FATAL
    }
    val finalNote = if (sawDeterministic) (lastDeterministicNote ?: lastNote) else lastNote
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
    val combined = buildBatchInput(items.map { it.first to it.second })
    val batchCtx = ctx.copy(verify = ctx.verify.copy(markerEnabled = false))
    val batchFormat = if (ctx.batchJsonFormat) buildBatchJsonFormat(items.size) else buildBatchFormat(items.size)
    val attempts = buildAttempts(
        batchCtx, combined,
        prevTranslatedTail = null, prevSourceTail = prevSourceTail, combined,
        batchFormat = batchFormat
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
    val isZeroIndexed = parsed.containsKey(0) && !parsed.containsKey(items.size)
    val saved = mutableListOf<String>()

    for ((idx, item) in items.withIndex()) {
        if (ctx.stopped()) break
        val (fileName, content) = item
        val seg = parsed[idx + 1] ?: if (isZeroIndexed) parsed[idx] else null
        val verified = seg?.let { verifyTranslation(content, it, ctx.verify.copy(markerEnabled = false)) }
        if (verified != null) {
            if (saveOutputText(store, outputDirUri, fileName, verified, log = ctx.log) != null) {
                completed++
                settled++
                done[idx] = true
                saved.add(fileName)
                ctx.log("batch saved: $fileName")
            }
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
 * 重複 "(1)" を作らない生成。プロバイダが同名衝突時に自動リネームする環境向け。
 * 技術的根拠1行：作成物の実名が要求と異なり、かつ衝突重複（" (1)"）の場合のみ消して既存を探し直す。拡張子正規化等は受容する。
 */
internal suspend fun findOrCreateFile(
    store: FileStore,
    dirUri: String,
    name: String,
    mime: String
): VDoc? {
    store.findChild(dirUri, name)?.let { return it }
    val created = store.createFile(dirUri, name, mime) ?: return null
    if (created.name.equals(name, ignoreCase = true)) return created
    val isCollisionRename = COLLISION_RENAME_REGEX.matches(created.name)
    if (!isCollisionRename) return created
    store.deleteFile(created.uri)
    return store.findChild(dirUri, name)
}

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
 * 唯一の訳文保存実装。別名に書く→照合→確定の順で公開し、保存済み文書またはnullを返す。
 * 確定は置換対応時のみ置換し、非対応時は直接確定＋照合に退行する。いずれも照合不一致は失敗とする。
 * 技術的根拠1行：未検証の上書きを全経路でなくし、異常終了のどの時点でも欠落を完成と誤認しない方向に収束させる。
 */
suspend fun saveOutputText(
    store: FileStore,
    dirUri: String,
    fileName: String,
    text: String,
    mime: String = "text/plain",
    existing: VDoc? = null,
    log: (String) -> Unit = {}
): VDoc? {
    if (existing != null) {
        // 同一文書への上書きはURIを保つため直接書込＋照合とする
        if (!store.writeText(existing.uri, text)) {
            log("output save error (write): $fileName")
            return null
        }
        if (!verifyBytes(store, existing.uri, fileName, text, log)) return null
        return existing
    }
    // 別名は完成判定から不可視な接頭辞にし、異常終了の残骸は次回保存時に自己掃除する
    val tmpName = ".tmp_" + fileName.replace('.', '_')
    try {
        store.findChild(dirUri, tmpName)?.let { if (!it.isDirectory) store.deleteFile(it.uri) }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("tmp sweep failed: $tmpName ${t.message}")
    }
    val tmp = findOrCreateFile(store, dirUri, tmpName, mime)
        ?: run {
            log("output save error (create): $fileName")
            return null
        }
    if (!store.writeText(tmp.uri, text)) {
        log("output save error (write): $fileName")
        deleteQuietly(store, tmp.uri, log)
        return null
    }
    if (!verifyBytes(store, tmp.uri, fileName, text, log)) {
        deleteQuietly(store, tmp.uri, log)
        return null
    }
    // 確定：置換を試みる。旧成果物は置換成功時に置き換わり、非対応時は直接確定＋照合に退行する。
    try {
        store.renameFile(dirUri, tmp.uri, fileName)?.let { return it }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 非対応時は退行経路へ
    }
    // 退行：直接作成＋書込＋照合
    deleteQuietly(store, tmp.uri, log)
    val direct = findOrCreateFile(store, dirUri, fileName, mime)
        ?: run {
            log("output save error (create): $fileName")
            return null
        }
    if (!store.writeText(direct.uri, text)) {
        log("output save error (write): $fileName")
        return null
    }
    if (!verifyBytes(store, direct.uri, fileName, text, log)) return null
    return direct
}

/** 最善努力の削除。失敗は記録のみで本流を止めない。 */
internal suspend fun deleteQuietly(store: FileStore, fileUri: String, log: (String) -> Unit) {
    try {
        store.deleteFile(fileUri)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("cleanup failed: $fileUri ${t.message}")
    }
}

/**
 * 書込内容の照合。確定済みURIから直接読み返して全文照合する。
 * 技術的根拠1行：作成直後の文書は一覧未反映・表示名改変があり得るため名寄せ再検索を使わず、確定済みURIで照合する。
 */
suspend fun verifyBytes(
    store: FileStore,
    fileUri: String,
    fileName: String,
    text: String,
    log: (String) -> Unit = {}
): Boolean {
    val read = try {
        store.readText(fileUri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    if (read == text) return true
    log("output verify error (content): $fileName")
    return false
}

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
 *    （訳文末尾と原文末尾の重ね注入はしない。buildSystemPrompt側で単一化する）
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
