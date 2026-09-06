package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.AcquireResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.RequestOptions
import com.example.novelscraper.translation.v2.domain.ThinkingSupport
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.domain.resolveDouble
import com.example.novelscraper.translation.v2.domain.resolveInt
import com.example.novelscraper.translation.v2.domain.resolveOption
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import kotlinx.coroutines.delay

/** ハンドラー生成。資格情報（キー）とエンドポイントの束縛は呼出側の責務 */
typealias HandlerFactory = (profile: V2ModelProfile, key: String) -> ProviderHandler

/**
 * プロファイルを解決済み要求オプションへ変換する（pure）。
 * 能力表にない項目は落とす（表示＝有効の送信側対応）。
 */
fun resolveProfileOptions(
    profile: V2ModelProfile,
    descriptor: ProviderDescriptor?
): RequestOptions {
    val caps = descriptor?.capabilitiesFor(profile.model)
    val thinkingLevel = when (val t = caps?.thinking) {
        is ThinkingSupport.Levels -> resolveOption(t.supported, null, profile.thinkingLevel)
        else -> null
    }
    val thinkingBudget = when (val t = caps?.thinking) {
        is ThinkingSupport.Budget -> resolveInt(t.range, null, profile.thinkingBudget).value
        else -> null
    }
    val sampling = caps?.sampling ?: emptyMap()
    return RequestOptions(
        temperature = resolveDouble(sampling["temperature"], null, profile.temperature).value,
        topP = resolveDouble(sampling["topP"], null, profile.topP).value,
        repetitionPenalty = resolveDouble(sampling["repetitionPenalty"], null, profile.repetitionPenalty).value,
        thinkingLevel = thinkingLevel,
        thinkingBudget = thinkingBudget,
        maxOutputTokens = profile.maxOutputTokens?.coerceIn(1000, 200000),
        jsonSchema = if (profile.useJsonSchema && caps?.structuredOutput == true) "batch" else null
    )
}

/**
 * ワーカー専有の巡回器。モデル×プロンプト巡回、同一スコープ内の待機再送、
 * キー交代を一本化する。非管理プロバイダーは単発＋ bounded 再送のみ行う。
 */
class Rotation(
    private val workerId: Int,
    private val profiles: List<V2ModelProfile>,
    private val pool: QuotaPool?,
    private var keyIndex: Int,
    private var key: String,
    private val descriptors: Map<String, ProviderDescriptor>,
    private val handlerFactory: HandlerFactory,
    private val openRouterKey: String,
    private val switchCooldownSec: Int = 15,
    private val maxSameRetries: Int = 2,
    private val sendGate: V2SendGate? = null,
    private val sendGateIntervalMs: Long = 10_000L,
    private val stopped: () -> Boolean = { false },
    private val meter: CostMeter? = null,
    private val log: (String) -> Unit = {}
) {
    var exhausted: Boolean = false
        private set

    private fun geminiModels(): List<String> =
        profiles.filter { it.providerId == "gemini" }.map { it.model }

    private fun scopesFor(profile: V2ModelProfile): List<String> {
        val descriptor = descriptors[profile.providerId] ?: return listOf(profile.model)
        return listOf(descriptor.quotaScopeOf(profile.model))
    }

    private fun managed(profile: V2ModelProfile): Boolean =
        profile.providerId == "gemini" && pool != null

    private fun keyFor(profile: V2ModelProfile): String {
        return when (profile.providerId) {
            "gemini" -> key
            else -> openRouterKey
        }
    }

    private suspend fun callOnce(profile: V2ModelProfile, prompt: String, source: String): LlmResult {
        sendGate?.acquire(sendGateIntervalMs)
        val descriptor = descriptors[profile.providerId]
        val options = resolveProfileOptions(profile, descriptor)
            val handler = try {
                handlerFactory(profile, keyFor(profile))
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

    /**
     * モデル×プロンプト巡回。成功で即返却し、全滅時は最終失敗を返す。
     * 技術的根拠1行：再試行（同一スコープ）と退避（モデル→キー）を一層で直列化し、二重管理をなくす。
     */
    suspend fun execute(
        prompts: List<String>,
        source: String,
        profilePrompts: Map<String, List<String>>? = null
    ): LlmResult {
        if (profiles.isEmpty() || stopped() || exhausted) {
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
        }
        var lastFailure: LlmResult.Failure? = null
        var totalGuard = profiles.size * (prompts.size + 2) * 2 + 8

        keyEpoch@ while (!stopped() && !exhausted) {
            if (totalGuard-- <= 0) {
                return lastFailure
                    ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "rotation-cap"))
            }
            var quotaSeenThisEpoch = false
            val failedScopes = linkedSetOf<Pair<Int, String>>()
            val dailyScopes = mutableSetOf<Pair<Int, String>>()
            var attemptedAny = false

            for (profile in profiles) {
                if (stopped() || exhausted) break
                // 枯渇ペアは飛ばして無駄打ち防止
                if (managed(profile)) {
                    val pool = pool ?: continue
                    val dead = scopesFor(profile).all { pool.isScopeDead(keyIndex, it) }
                    if (dead) {
                        log("[W#$workerId] skip dead pair: ${profile.model}")
                        continue
                    }
                }
                val activePrompts = profilePrompts?.get(profile.id)?.ifEmpty { prompts } ?: prompts
                for (prompt in activePrompts) {
                    if (stopped() || exhausted) break
                    var sameLeft = maxSameRetries
                    while (true) {
                        if (stopped() || exhausted) break
                        attemptedAny = true
                        when (val result = callOnce(profile, prompt, source)) {
                            is LlmResult.Success -> return result
                            is LlmResult.Failure -> {
                                lastFailure = result
                                when (result.failure.kind) {
                                    FailureKind.BLOCKED_DETERMINISTIC,
                                    FailureKind.CONFIG,
                                    FailureKind.FATAL -> break
                                    FailureKind.QUOTA_DAILY, FailureKind.QUOTA_MINUTE,
                                    FailureKind.RETRYABLE_AFTER -> {
                                        quotaSeenThisEpoch = true
                                        if (managed(profile)) {
                                            val scope = scopesFor(profile).firstOrNull() ?: profile.model
                                            failedScopes.add(keyIndex to scope)
                                            if (result.failure.kind == FailureKind.QUOTA_DAILY) {
                                                dailyScopes.add(keyIndex to scope)
                                            }
                                        }
                                        val waitSec = result.failure.retryAfterSec
                                            ?.toLong()?.coerceIn(5, 120)
                                            ?: if (managed(profile)) 2L else 5L
                                        if (sameLeft > 0) {
                                            sameLeft--
                                            delay(waitSec * 1000L)
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

            // 1周して成功なし：全プロファイルが枯渇skipなら終了、制限系なしなら確定失敗
            if (!attemptedAny) {
                exhausted = true
                log("[W#$workerId] all pairs dead, worker ends")
                return LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_DAILY, note = "all-pairs-dead"))
            }
            if (!quotaSeenThisEpoch) return lastFailure
                ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "no-attempt"))
            // 制限系あり：失敗ペアを報告して新キーへ
            val pool = pool
            if (pool == null) {
                return lastFailure
                    ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "no-pool"))
            }
            for ((k, scope) in failedScopes) {
                pool.reportQuota(k, scope, (k to scope) in dailyScopes, switchCooldownSec)
            }
            when (val claimed = pool.acquire(geminiModels())) {
                is AcquireResult.Ready -> {
                    keyIndex = claimed.credentialIndex
                    key = claimed.credential
                    log("[W#$workerId] rotated to key[$keyIndex]")
                    continue@keyEpoch
                }
                is AcquireResult.Wait -> {
                    log("[W#$workerId] pool cooling, wait ${claimed.waitMillis / 1000}s")
                    delay(claimed.waitMillis)
                    continue@keyEpoch
                }
                is AcquireResult.Exhausted -> {
                    exhausted = true
                    log("[W#$workerId] pool exhausted, worker ends")
                    return lastFailure
                        ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "exhausted"))
                }
            }
        }
        return lastFailure ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
    }

    suspend fun release() {
        pool?.release(keyIndex)
    }
}
