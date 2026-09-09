package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.isDeterministicFailure
import com.example.novelscraper.translation.v2.domain.isQuotaLike
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
    var sameRetries = 0
    var lastDriver: String? = null
    var lastNote = ""

    var lastDeterministicKind: FailureKind? = null
    var lastDeterministicNote: String? = null

    for (attempt in attempts) {
        if (options.stopped()) return DriverOutcome.Stopped
        if (attempt.driverName != lastDriver) {
            sameRetries = 0
            lastDriver = attempt.driverName
        }
        // 同一ドライバー内の待機再送ループ
        while (true) {
            if (options.stopped()) return DriverOutcome.Stopped
            when (val result = attempt.call()) {
                is LlmResult.Success -> {
                    val meter = options.meter
                    if (meter != null && !meter.add(
                            tokens = (result.promptTokens + result.completionTokens).toLong(),
                            cost = 0.0
                        )
                    ) {
                        options.log("cost cap reached, halt")
                        return DriverOutcome.Stopped
                    }
                    return DriverOutcome.Ok(result.text, result.promptTokens, result.completionTokens)
                }
                is LlmResult.Failure -> {
                    lastNote = result.failure.note
                    lastFailureKind = result.failure.kind
                    when (result.failure.kind) {
                        FailureKind.BLOCKED_DETERMINISTIC, FailureKind.FATAL -> {
                            sawDeterministic = true
                            lastDeterministicKind = result.failure.kind
                            lastDeterministicNote = result.failure.note
                            break
                        }
                        FailureKind.CONFIG -> {
                            sawConfig = true
                            break
                        }
                        FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> {
                            sawTransient = true
                            if (sameRetries < options.maxSameRetries) {
                                sameRetries++
                                delay(options.retryDelayMs(sameRetries))
                                continue
                            }
                            break
                        }
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
    // 技術的根拠1行：辞書照合はチャンク本文のみに依存するため試行ループ外で1回だけ行う
    // 完全一致ゼロ時は参考例を入れる（表記揺れでも表記パターンを伝える。旧版の例示フォールバック復活）
    val dictEntries: List<String>
    val dictExampleFallback: Boolean
    val dict = ctx.dictionary
    if (dict == null) {
        dictEntries = emptyList()
        dictExampleFallback = false
    } else {
        val matched = matchDictionaryEntries(chunkText, dict.characters, dict.genders)
        if (matched.isNotEmpty()) {
            dictEntries = matched
            dictExampleFallback = false
        } else {
            dictEntries = matchDictionaryExamples(dict.characters, dict.genders)
            dictExampleFallback = dictEntries.isNotEmpty()
        }
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
                batchFormat = batchFormat,
                dictionaryExampleFallback = dictExampleFallback
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

    // 辞書照合はチャンク本文のみに依存するため試行ループ外で1回だけ行う
    val dict = ctx.dictionary
    val dictEntries: List<String>
    val dictExampleFallback: Boolean
    if (dict == null) {
        dictEntries = emptyList()
        dictExampleFallback = false
    } else {
        val matched = matchDictionaryEntries(content, dict.characters, dict.genders)
        if (matched.isNotEmpty()) {
            dictEntries = matched
            dictExampleFallback = false
        } else {
            dictEntries = matchDictionaryExamples(dict.characters, dict.genders)
            dictExampleFallback = dictEntries.isNotEmpty()
        }
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
                batchFormat = null,
                dictionaryExampleFallback = dictExampleFallback
            )

            var sameRetries = 0
            while (true) {
                if (ctx.stopped()) return SingleResult.Stopped
                // 技術的根拠: 第1引数に promptNum.toString() を渡し、bindCall側で試行中のプロンプト番号を正しく識別可能にする
                when (val result = ctx.call(promptNum.toString(), prompt, source)) {
                    is LlmResult.Success -> {
                        val meter = ctx.meter
                        if (meter != null && !meter.add(
                                tokens = (result.promptTokens + result.completionTokens).toLong(),
                                cost = 0.0
                            )
                        ) {
                            ctx.log("cost cap reached, halt")
                            return SingleResult.Stopped
                        }

                        // 品質チェック
                        val verified = verifyTranslation(content, result.text, ctx.verify)
                        if (verified != null) {
                            if (promptIdx > 0) {
                                ctx.log("✨ プロンプト#$promptNum でのリトライに成功しました")
                            }
                            return SingleResult.Translated(verified)
                        }

                        // 品質チェック不合格
                        val rejectReason = verifyRejectReason(content, result.text, ctx.verify) ?: "verify-rejected"
                        ctx.log("⚠️ 品質チェック不合格 ($rejectReason): プロンプト#$promptNum (${promptIdx + 1}/${promptOrder.size})")
                        lastFailureKind = FailureKind.FATAL
                        lastNote = rejectReason
                        // 次のプロンプトへ進む
                        break
                    }
                    is LlmResult.Failure -> {
                        lastNote = result.failure.note
                        lastFailureKind = result.failure.kind
                        when (result.failure.kind) {
                            FailureKind.BLOCKED_DETERMINISTIC, FailureKind.FATAL -> {
                                sawDeterministic = true
                                lastDeterministicKind = result.failure.kind
                                lastDeterministicNote = result.failure.note
                                break
                            }
                            FailureKind.CONFIG -> {
                                sawConfig = true
                                break
                            }
                            FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> {
                                sawTransient = true
                                if (sameRetries < ctx.maxSameRetries) {
                                    sameRetries++
                                    delay(1000L * sameRetries)
                                    continue
                                }
                                break
                            }
                        }
                    }
                }
                break
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
            val out = findOrCreateFile(store, outputDirUri, fileName, "text/plain")
            if (out != null && store.writeText(out.uri, verified)) {
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
                val out = findOrCreateFile(store, outputDirUri, fileName, "text/plain")
                if (out != null && store.writeText(out.uri, r.text)) {
                    completed++
                    done[i] = true
                    saved.add(fileName)
                }
                settled++
            }
            is SingleResult.Failed -> {
                if (r.isDeterministic || com.example.novelscraper.translation.v2.domain.isDeterministicFailure(r.terminal, r.note)) {
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
    val isCollisionRename = Regex(""".*\s\(\d+\).*""").matches(created.name)
    if (!isCollisionRename) return created
    store.deleteFile(created.uri)
    return store.findChild(dirUri, name)
}

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
    /** 上限（これを超えたらfalseを返して呼出側でスキップ扱いにする） */
    val maxInputBytes: Int = 10_000_000
)

/**
 * 大ファイル翻訳（チャンク翻訳）。
 * in/ への分割書出しまではメモリ、以降は1チャンクずつ処理し、
 * 結果は追記ストリーミングで結合する（全文保持しない）。
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
): Boolean {
    if (ctx.stopped()) return false
    if (utf8Bytes(content) > options.maxInputBytes) {
        ctx.log("large: over input cap, skip")
        return false
    }
    val inDir = store.findChild(workDirUri, "in") ?: store.createDir(workDirUri, "in") ?: return false
    val outDir = store.findChild(workDirUri, "out") ?: store.createDir(workDirUri, "out") ?: return false

    val chunkNames = writeChunks(store, inDir.uri, content, options.chunkSizeBytes, ctx.log)
    if (chunkNames.isEmpty()) return false

    var prevTail: String? = null
    // Resume: rebuild the context from finished chunks.
    // The raw-source tail is injected into the first chunk only (never stacked).
    var firstPending = true
    for ((chunkIdx, name) in chunkNames.withIndex()) {
        if (ctx.stopped()) return false
        onChunkProgress?.invoke(chunkIdx + 1, chunkNames.size)
        val existing = store.findChild(outDir.uri, name)
        val saved = existing?.let { store.readText(it.uri) }
        if (!saved.isNullOrBlank() && store.findChild(outDir.uri, "$name.failed") == null) {
            prevTail = saved.lines().takeLast(options.tailLines).joinToString("\n")
            firstPending = false
            continue
        }
        if (store.findChild(outDir.uri, "$name.failed") != null) {
            ctx.log("large: unresolved $name.failed, halt")
            return false
        }
        val chunkDoc = store.findChild(inDir.uri, name) ?: return false
        val chunkText = store.readText(chunkDoc.uri) ?: return false
        val srcTail = if (firstPending) prevSourceTail else null
        firstPending = false
        when (val r = translateSingle(chunkText, ctx, prevTranslatedTail = prevTail, prevSourceTail = srcTail)) {
            is SingleResult.Translated -> {
                val out = findOrCreateFile(store, outDir.uri, name, "text/plain")
                if (out == null || !store.writeText(out.uri, r.text)) return false
                prevTail = r.text.lines().takeLast(options.tailLines).joinToString("\n")
            }
            is SingleResult.Failed -> {
                ctx.log("large: chunk $name failed (${r.terminal} ${r.note})".trim())
                if (r.isDeterministic || isDeterministicFailure(r.terminal, r.note)) {
                    writeFailed(store, outDir.uri, name, chunkText, ctx.log)
                }
                return false
            }
            is SingleResult.ConfigOnly -> return false
            is SingleResult.Stopped -> return false
        }
    }

    if (ctx.stopped()) return false
    // 全件揃い確認（1件ずつ読む）
    for (name in chunkNames) {
        val out = store.findChild(outDir.uri, name) ?: return false
        if (store.readText(out.uri).isNullOrBlank()) return false
    }
    val finalDoc = findOrCreateFile(store, outputDirUri, fileName, "text/plain")
        ?: return false
    if (!joinOutputsStreaming(store, outDir.uri, chunkNames, finalDoc.uri, ctx.log)) return false
    store.deleteRecursively(workDirUri)
    return true
}
