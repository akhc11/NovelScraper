package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.RetryPolicy
import com.example.novelscraper.translation.v2.domain.Sleeper
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.settings.V2ModelProfile

/**
 * モデル巡回の最小契約。管理（Gemini・キー巡回）と非管理（単一キー再送）を型で分離する。
 * 技術的根拠1行：経路選択を実行時分岐にするとプール誤用（S-2）が再発するため、生成時に型で固定する。
 */
interface PromptRouter {
    val exhausted: Boolean

    suspend fun execute(
        prompts: List<String>,
        source: String,
        profilePrompts: Map<String, List<String>>? = null,
        forBatch: Boolean = false,
        /**
         * 推敲用のプロファイル上書き（id→差し替え）。null/欠落＝保持プロファイルをそのまま使う。
         * 技術的根拠1行：第二巡回器を作らず同一の巡回・待機・クォータ管理に乗せるため、差分だけをid指定で渡す。
         */
        profileOverrides: Map<String, V2ModelProfile>? = null
    ): LlmResult

    suspend fun release()
}

/** エポック上限。無限巡回の防止（両戦略で共有）。 */
internal fun epochGuard(profileCount: Int, promptCount: Int): Int =
    profileCount * (promptCount + 2) * 2 + 8

/**
 * 中断可能な待機。停止旗を見ながら1秒刻みで眠り、停止時は残りを捨てる。
 * 技術的根拠1行：一括睡眠では停止ボタンが最大10分遅れるため、待機の合計秒数は変えずに応答性だけを上げる。
 */
suspend fun patientSleep(
    totalMs: Long,
    stopped: () -> Boolean,
    sleeper: Sleeper
) {
    var remaining = totalMs.coerceAtLeast(0)
    while (remaining > 0) {
        if (stopped()) return
        val slice = minOf(remaining, 1000L)
        sleeper(slice)
        remaining -= slice
    }
}

/**
 * 単発呼出の共有実装（送信・コスト計上のみ。巡回・待機・中止判断は呼出側の責務）。
 */
internal suspend fun executeLlmCall(
    workerId: Int,
    sendGate: V2SendGate?,
    sendGateIntervalMs: Long,
    descriptor: ProviderDescriptor?,
    profile: V2ModelProfile,
    prompt: String,
    source: String,
    forBatch: Boolean,
    handlerFactory: HandlerFactory,
    key: String,
    meter: CostMeter?,
    log: (String) -> Unit
): LlmResult {
    sendGate?.acquire(sendGateIntervalMs)
    val options = resolveProfileOptions(profile, descriptor, forBatch)
    val handler = try {
        handlerFactory(profile, key)
    } catch (e: Exception) {
        return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "handler:${e.message}"))
    }
    val result = try {
        handler.call(LlmRequest(profile.providerId, profile.model, prompt, source, options))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "io:${e.message}"))
    }
    if (result is LlmResult.Success && meter != null) {
        if (!meter.add(tokens = (result.promptTokens + result.completionTokens).toLong())) {
            log("cost cap reached")
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "cost-cap"))
        }
    }
    return result
}

/**
 * 待機・再送判定の唯一の実装（一時系・制限系で共有）。
 * サーバ指定秒（Retry-After）を最優先し、なければ [RetryPolicy] の式で解く。
 * 技術的根拠1行：待機秒数解決・ログ・睡眠の分散実装は必ず乖離するため、式は方針に、手順はここに一本化する。
 */
internal suspend fun handleRetryableWait(
    failure: ClassifiedFailure,
    label: String,
    baseDelaySec: Long,
    capSec: Long,
    retriesUsed: Int,
    maxRetries: Int,
    policy: RetryPolicy = RetryPolicy(),
    sleeper: Sleeper,
    log: (String) -> Unit,
    stopped: () -> Boolean = { false }
): Boolean {
    if (retriesUsed >= maxRetries) return false
    val serverMs = failure.retryAfterSec?.toLong()
        ?.coerceIn(0L, TranslationLimits.RETRY_AFTER_MAX_SEC)?.times(1000L)
    val waitMs = serverMs
        ?: policy.copy(baseDelayMs = baseDelaySec * 1000L, maxDelayMs = capSec * 1000L)
            .delayForAttempt(retriesUsed + 1)
    if (waitMs > 0) {
        log("$label のため ${waitMs / 1000L}秒待機して再送します")
        patientSleep(waitMs, stopped, sleeper)
    } else {
        log("$label のため即座に再送します")
    }
    return true
}
