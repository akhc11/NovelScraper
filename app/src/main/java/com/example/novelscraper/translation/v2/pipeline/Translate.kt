package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.infra.FileStore
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
    data class GiveUp(val terminal: FailureKind, val configOnly: Boolean) : DriverOutcome
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
 * エンジン経路では Rotation がモデル巡回を担うため、同一切替なし（maxSameRetries=0）で使う。
 * コスト計は Rotation 側に一本化し、ここでは付けないこと（二重計上防止）。
 */
suspend fun attemptDrivers(attempts: List<Attempt>, options: AttemptOptions = AttemptOptions()): DriverOutcome {
    var sawConfig = false
    var sawOther = false
    var sameRetries = 0
    var lastDriver: String? = null

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
                    when (result.failure.kind) {
                        FailureKind.BLOCKED_DETERMINISTIC, FailureKind.CONFIG, FailureKind.FATAL -> {
                            if (result.failure.kind == FailureKind.CONFIG) sawConfig = true
                            else sawOther = true
                            break
                        }
                        FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE, FailureKind.RETRYABLE_AFTER -> {
                            sawOther = true
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
    // すべて設定エラーのみなら .failed を作らない（設定修正後に再実行可能にする）
    val terminal = if (sawConfig && !sawOther) FailureKind.CONFIG else FailureKind.FATAL
    return DriverOutcome.GiveUp(terminal, configOnly = sawConfig && !sawOther)
}

/** 入力規模による経路判定（pure）。しきい値は呼出側（目標出力からの逆算値）指定 */
enum class Route { SINGLE, BATCHABLE, LARGE }

fun routeFor(contentBytes: Int, splitThresholdBytes: Int): Route {
    return when {
        contentBytes <= 0 -> Route.SINGLE
        contentBytes > splitThresholdBytes -> Route.LARGE
        else -> Route.BATCHABLE
    }
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
    data object Failed : SingleResult
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
    for (driver in ctx.driverNames) {
        for (promptNum in ctx.promptOrder.ifEmpty { listOf(1, 1) }) {
            val base = ctx.basePrompts[promptNum] ?: ctx.basePrompts.values.firstOrNull() ?: ""
            val dictEntries = ctx.dictionary?.let {
                matchDictionaryEntries(chunkText, it.characters, it.genders)
            } ?: emptyList()
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
            attempts.add(Attempt(driver, prompt, source) { ctx.call(driver, prompt, source) })
        }
    }
    return attempts
}

/**
 * 単体翻訳。成功時は検証済み訳文、全滅時は失敗種別を返す。
 */
suspend fun translateSingle(
    content: String,
    ctx: TranslateContext,
    prevTranslatedTail: String? = null,
    prevSourceTail: String? = null
): SingleResult {
    if (ctx.stopped()) return SingleResult.Stopped
    val attempts = buildAttempts(ctx, content, prevTranslatedTail, prevSourceTail, content)
    return when (val outcome = attemptDrivers(
        attempts,
        AttemptOptions(
            maxSameRetries = ctx.maxSameRetries,
            stopped = ctx.stopped,
            meter = ctx.meter,
            log = ctx.log
        )
    )) {
        is DriverOutcome.Ok -> {
            val verified = verifyTranslation(content, outcome.text, ctx.verify)
            if (verified != null) SingleResult.Translated(verified) else SingleResult.Failed
        }
        is DriverOutcome.GiveUp ->
            if (outcome.configOnly) SingleResult.ConfigOnly else SingleResult.Failed
        is DriverOutcome.Stopped -> SingleResult.Stopped
    }
}

data class BatchOutcome(val completed: Int, val settled: Int, val configBlocked: Boolean = false)

/**
 * バッチ翻訳。成功分は即保存し、欠落・検証NG分のみ単体へ落とす。
 * 全滅時は単体フォールバックへ回す（設定のみ全滅時は .failed を作らず中断扱い）。
 */
suspend fun translateBatch(
    store: FileStore,
    outputDirUri: String,
    items: List<Pair<String, String>>,
    ctx: TranslateContext,
    prevSourceTail: String? = null
): BatchOutcome {
    if (items.isEmpty() || ctx.stopped()) return BatchOutcome(0, 0)
    val combined = buildBatchInput(items.map { it.first to it.second })
    val batchCtx = ctx.copy(verify = ctx.verify.copy(markerEnabled = false))
    val attempts = buildAttempts(
        batchCtx, combined,
        prevTranslatedTail = null, prevSourceTail = prevSourceTail, combined,
        batchFormat = buildBatchFormat(items.size)
    )
    val outcome = attemptDrivers(
        attempts,
        AttemptOptions(maxSameRetries = ctx.maxSameRetries, stopped = ctx.stopped, meter = ctx.meter, log = ctx.log)
    )
    val parsed = if (outcome is DriverOutcome.Ok) parseBatchResponse(outcome.text) else null
    if (parsed == null) {
        if (outcome is DriverOutcome.GiveUp && outcome.configOnly) return BatchOutcome(0, 0, configBlocked = true)
        if (ctx.stopped()) return BatchOutcome(0, 0)
        // All drivers failed -> single fallback (keeps the batch-level prev tail).
        return fallbackToSingles(store, outputDirUri, items, ctx, prevSourceTail)
    }

    var completed = 0
    var settled = 0
    val missing = mutableListOf<Pair<String, String>>()
    for ((idx, item) in items.withIndex()) {
        if (ctx.stopped()) break
        val (fileName, content) = item
        val seg = parsed[idx + 1]
        val verified = seg?.let { verifyTranslation(content, it, ctx.verify.copy(markerEnabled = false)) }
        if (verified != null) {
            val out = store.findChild(outputDirUri, fileName)
                ?: store.createFile(outputDirUri, fileName, "text/plain")
            if (out != null && store.writeText(out.uri, verified)) {
                completed++
                settled++
                ctx.log("batch saved: $fileName")
                continue
            }
        }
        missing.add(item)
    }
    if (missing.isNotEmpty() && !ctx.stopped()) {
        val fb = fallbackToSingles(store, outputDirUri, missing, ctx, prevSourceTail)
        completed += fb.completed
        settled += fb.settled
        if (fb.configBlocked) return BatchOutcome(completed, settled, configBlocked = true)
    }
    return BatchOutcome(completed, settled)
}

private suspend fun fallbackToSingles(
    store: FileStore,
    outputDirUri: String,
    items: List<Pair<String, String>>,
    ctx: TranslateContext,
    prevSourceTail: String? = null
): BatchOutcome {
    var completed = 0
    var settled = 0
    for ((fileName, content) in items) {
        if (ctx.stopped()) break
        when (val r = translateSingle(content, ctx, prevSourceTail = prevSourceTail)) {
            is SingleResult.Translated -> {
                val out = store.findChild(outputDirUri, fileName)
                    ?: store.createFile(outputDirUri, fileName, "text/plain")
                if (out != null && store.writeText(out.uri, r.text)) {
                    completed++
                }
                settled++
            }
            is SingleResult.Failed -> {
                writeFailed(store, outputDirUri, fileName, content, ctx.log)
                settled++
            }
            is SingleResult.ConfigOnly -> return BatchOutcome(completed, settled, configBlocked = true)
            is SingleResult.Stopped -> break
        }
    }
    return BatchOutcome(completed, settled)
}

suspend fun writeFailed(
    store: FileStore,
    outputDirUri: String,
    fileName: String,
    content: String,
    log: (String) -> Unit = {}
): Boolean {
    val failedName = "$fileName.failed"
    val doc = store.findChild(outputDirUri, failedName)
        ?: store.createFile(outputDirUri, failedName, "text/plain")
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
    val maxInputBytes: Int = 200_000_000
)

/**
 * 大ファイル翻訳。in/ への分割書出しまではメモリ、以降は1チャンクずつ処理し、
 * 結果は追記ストリーミングで結合する（全文保持しない）。
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
        ctx.log("large: over input cap, route to physical split")
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
                val out = store.findChild(outDir.uri, name)
                    ?: store.createFile(outDir.uri, name, "text/plain")
                if (out == null || !store.writeText(out.uri, r.text)) return false
                prevTail = r.text.lines().takeLast(options.tailLines).joinToString("\n")
            }
            is SingleResult.Failed -> {
                writeFailed(store, outDir.uri, name, chunkText, ctx.log)
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
    val finalDoc = store.findChild(outputDirUri, fileName)
        ?: store.createFile(outputDirUri, fileName, "text/plain")
        ?: return false
    if (!joinOutputsStreaming(store, outDir.uri, chunkNames, finalDoc.uri, ctx.log)) return false
    store.deleteRecursively(workDirUri)
    return true
}
