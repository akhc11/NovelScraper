package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import kotlinx.coroutines.delay

/**
 * 非管理プロバイダー専用巡回器（OpenRouter等の単一キー共有）。
 * プールに触れない。制限時は同一キーのbounded冷却で次周する（上限はepochGuard）。
 * 枯渇概念がないため [exhausted] は常に偽（ワーカー終了は停止旗・確定失敗で判断する）。
 */
class UnmanagedRotation(
    private val workerId: Int,
    private val profiles: List<V2ModelProfile>,
    private val key: String,
    private val descriptors: Map<ProviderId, ProviderDescriptor>,
    private val handlerFactory: HandlerFactory,
    private val cooldownSec: Int = 30,
    private val transientRetryDelaySec: Int = 2,
    private val maxSameRetries: Int = 2,
    private val sendGate: V2SendGate? = null,
    private val sendGateIntervalMs: Long = 10_000L,
    private val stopped: () -> Boolean = { false },
    private val meter: CostMeter? = null,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {}
) : PromptRouter {
    override var exhausted: Boolean = false
        private set

    override suspend fun execute(
        prompts: List<String>,
        source: String,
        profilePrompts: Map<String, List<String>>?,
        forBatch: Boolean
    ): LlmResult {
        if (profiles.isEmpty() || stopped()) {
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
        }
        var lastFailure: LlmResult.Failure? = null
        var totalGuard = epochGuard(profiles.size, prompts.size)

        while (!stopped()) {
            if (totalGuard-- <= 0) {
                return lastFailure
                    ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "rotation-cap"))
            }
            var quotaSeenThisEpoch = false

            for (profile in profiles) {
                if (stopped()) break
                val activePrompts = profilePrompts?.get(profile.id)?.ifEmpty { prompts } ?: prompts
                for (prompt in activePrompts) {
                    if (stopped()) break
                    var sameLeft = maxSameRetries
                    while (true) {
                        if (stopped()) break
                        when (val result = executeLlmCall(
                            workerId,
                            sendGate,
                            sendGateIntervalMs,
                            profile.providerId.toProviderId()?.let { descriptors[it] },
                            profile,
                            prompt,
                            source,
                            forBatch,
                            handlerFactory,
                            key,
                            meter,
                            log
                        )) {
                            is LlmResult.Success -> return result
                            is LlmResult.Failure -> {
                                lastFailure = result
                                when (result.failure.kind) {
                                    FailureKind.BLOCKED_DETERMINISTIC,
                                    FailureKind.CONFIG,
                                    FailureKind.FATAL -> break
                                    FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE -> {
                                        quotaSeenThisEpoch = true
                                        if (handleQuotaRetry(result.failure, cooldownSec, TranslationLimits.UNMANAGED_COOLDOWN_MAX_SEC.toLong(), sameLeft, sleeper, log)) {
                                            sameLeft--
                                            continue
                                        }
                                        break
                                    }
                                    FailureKind.RETRYABLE_AFTER -> {
                                        if (handleTransientRetry(result.failure, transientRetryDelaySec, sameLeft, sleeper, log)) {
                                            sameLeft--
                                            continue
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!quotaSeenThisEpoch) return lastFailure
                ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "no-attempt"))
            val cooldown = cooldownSec.coerceIn(0, TranslationLimits.UNMANAGED_COOLDOWN_MAX_SEC)
            if (cooldown > 0) {
                log("制限のため ${cooldown}秒冷却して次周します")
                sleeper(cooldown * 1000L)
            }
        }
        return lastFailure ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
    }

    override suspend fun release() = Unit
}
