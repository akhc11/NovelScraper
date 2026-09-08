package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
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
        forBatch: Boolean = false
    ): LlmResult

    suspend fun release()
}

/** エポック上限。無限巡回の防止（両戦略で共有）。 */
internal fun epochGuard(profileCount: Int, promptCount: Int): Int =
    profileCount * (promptCount + 2) * 2 + 8

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
            log("[W#$workerId] cost cap reached")
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "cost-cap"))
        }
    }
    return result
}
