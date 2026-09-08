package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.AcquireResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.RequestOptions
import com.example.novelscraper.translation.v2.domain.ThinkingSupport
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.domain.resolveDouble
import com.example.novelscraper.translation.v2.domain.resolveInt
import com.example.novelscraper.translation.v2.domain.resolveOption
import com.example.novelscraper.translation.v2.settings.V2ModelProfile

/** ハンドラー生成。資格情報（キー）とエンドポイントの束縛は呼出側の責務 */
typealias HandlerFactory = (profile: V2ModelProfile, key: String) -> ProviderHandler

/**
 * プロファイルを解決済み要求オプションへ変換する（pure）。
 * 技術的根拠1行：能力表に載らないサンプリング項目は落として400級誤爆を防ぐが、記述子自体が未知の場合は寛容に素通しする。
 * 構造化出力はバッチ枠でのみ有効化する（単体・チャンクは素の訳文を返す必要があるため）。
 */
fun resolveProfileOptions(
    profile: V2ModelProfile,
    descriptor: ProviderDescriptor?,
    forBatch: Boolean = false
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
    fun gateSampling(key: String, value: Double?): Double? {
        if (value == null) return null
        val table = caps?.sampling ?: return resolveDouble(null, null, value).value
        val range = table[key] ?: return null
        return resolveDouble(range, null, value).value
    }
    val modelMaxTokens = caps?.maxOutputTokens ?: 65536
    val resolvedMaxTokens = profile.maxOutputTokens?.coerceIn(TranslationLimits.MIN_OUTPUT_TOKENS, modelMaxTokens)
        ?: modelMaxTokens
    return RequestOptions(
        temperature = gateSampling("temperature", profile.temperature),
        topP = gateSampling("topP", profile.topP),
        repetitionPenalty = gateSampling("repetitionPenalty", profile.repetitionPenalty),
        thinkingLevel = thinkingLevel,
        thinkingBudget = thinkingBudget,
        maxOutputTokens = resolvedMaxTokens,
        jsonSchema = if (forBatch && profile.useJsonSchema && caps?.structuredOutput == true) "batch" else null
    )
}

/**
 * 管理プロバイダー用巡回器。モデル×プロンプト巡回、同一スコープ内の待機再送、
 * キー交代を一本化する。非管理のみ構成は [UnmanagedRotation] を使うこと。
 */
class Rotation(
    private val workerId: Int,
    private val profiles: List<V2ModelProfile>,
    private val pool: QuotaPool?,
    private var keyIndex: Int,
    private var key: String,
    private val descriptors: Map<ProviderId, ProviderDescriptor>,
    private val handlerFactory: HandlerFactory,
    private val openRouterKey: String,
    private val geminiCooldownSec: Int = 60,
    private val transientRetryDelaySec: Int = 2,
    private val maxSameRetries: Int = 2,
    private val sendGate: V2SendGate? = null,
    private val sendGateIntervalMs: Long = 10_000L,
    private val stopped: () -> Boolean = { false },
    private val meter: CostMeter? = null,
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val log: (String) -> Unit = {}
) : PromptRouter {
    override var exhausted: Boolean = false
        private set

    private fun geminiModels(): List<String> =
        profiles.filter { it.providerId.toProviderId() == ProviderId.GEMINI }.map { it.model }

    private fun scopesFor(profile: V2ModelProfile): List<String> {
        val descriptor = profile.providerId.toProviderId()?.let { descriptors[it] }
            ?: return listOf(profile.model)
        return listOf(descriptor.quotaScopeOf(profile.model))
    }

    private fun managed(profile: V2ModelProfile): Boolean =
        profile.providerId.toProviderId() == ProviderId.GEMINI && pool != null

    private fun keyFor(profile: V2ModelProfile): String {
        return when (profile.providerId.toProviderId()) {
            ProviderId.GEMINI -> key
            else -> openRouterKey
        }
    }

    private suspend fun callOnce(profile: V2ModelProfile, prompt: String, source: String, forBatch: Boolean): LlmResult =
        executeLlmCall(
            workerId,
            sendGate,
            sendGateIntervalMs,
            profile.providerId.toProviderId()?.let { descriptors[it] },
            profile,
            prompt,
            source,
            forBatch,
            handlerFactory,
            keyFor(profile),
            meter,
            log
        )

    /**
     * モデル×プロンプト巡回。成功で即返却し、全滅時は最終失敗を返す。
     * 技術的根拠1行：再試行（同一スコープ）と退避（モデル→キー）を一層で直列化し、二重管理をなくす。
     */
    override suspend fun execute(
        prompts: List<String>,
        source: String,
        profilePrompts: Map<String, List<String>>?,
        forBatch: Boolean
    ): LlmResult {
        if (profiles.isEmpty() || stopped() || exhausted) {
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
        }
        var lastFailure: LlmResult.Failure? = null
        var totalGuard = epochGuard(profiles.size, prompts.size)

        keyEpoch@ while (!stopped() && !exhausted) {
            if (totalGuard-- <= 0) {
                return lastFailure
                    ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "rotation-cap"))
            }
            var quotaSeenThisEpoch = false
            val failedScopes = linkedSetOf<Pair<Int, String>>()
            val dailyScopes = mutableSetOf<Pair<Int, String>>()
            var attemptedAny = false

            profileLoop@ for (profile in profiles) {
                if (stopped() || exhausted) break
                // 枯渇ペアは飛ばして無駄打ち防止
                if (managed(profile)) {
                    val pool = pool ?: continue
                    val dead = scopesFor(profile).all { pool.isScopeDead(keyIndex, it) }
                    if (dead) {
                        log("skip dead pair: ${profile.model}")
                        continue
                    }
                }
                val activePrompts = profilePrompts?.get(profile.id)?.ifEmpty { prompts } ?: prompts
                promptLoop@ for (prompt in activePrompts) {
                    if (stopped() || exhausted) break
                    var sameLeft = maxSameRetries
                    while (true) {
                        if (stopped() || exhausted) break
                        attemptedAny = true
                        when (val result = callOnce(profile, prompt, source, forBatch)) {
                            is LlmResult.Success -> return result
                            is LlmResult.Failure -> {
                                lastFailure = result
                                when (result.failure.kind) {
                                    FailureKind.BLOCKED_DETERMINISTIC,
                                    FailureKind.CONFIG,
                                    FailureKind.FATAL -> break
                                    FailureKind.QUOTA_DAILY -> {
                                        quotaSeenThisEpoch = true
                                        if (managed(profile)) {
                                            val scope = scopesFor(profile).firstOrNull() ?: profile.model
                                            failedScopes.add(keyIndex to scope)
                                            dailyScopes.add(keyIndex to scope)
                                        }
                                        log("${profile.model} の日のLIMITに達したため同一キー内の次モデルへ移行します")
                                        // 日のLIMITは今日中回復しないため同一モデルでの待機再試行を0秒で打ち切り、同一キー内の次モデルへ即時切替
                                        break@promptLoop
                                    }
                                    FailureKind.QUOTA_MINUTE -> {
                                        quotaSeenThisEpoch = true
                                        if (managed(profile)) {
                                            val scope = scopesFor(profile).firstOrNull() ?: profile.model
                                            failedScopes.add(keyIndex to scope)
                                        }
                                        if (handleQuotaRetry(result.failure, geminiCooldownSec, TranslationLimits.COOLDOWN_MAX_SEC.toLong(), sameLeft, sleeper, log)) {
                                            sameLeft--
                                            continue
                                        }
                                        break
                                    }
                                    FailureKind.RETRYABLE_AFTER -> {
                                        // 技術的根拠1行：5xxや通信一時エラーはクォータ枯渇ではないためプール報告せず、一時エラー待機設定で再試行する。
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

            // 1周して成功なし：全プロファイルが枯渇skipなら終了、制限系なしなら確定失敗
            if (!attemptedAny) {
                exhausted = true
                log("all pairs dead, worker ends")
                return LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_DAILY, note = "all-pairs-dead"))
            }
            if (!quotaSeenThisEpoch) return lastFailure
                ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "no-attempt"))
            // 技術的根拠1行：非管理のみ構成は [UnmanagedRotation] の責務であり、ここでプールに触れると誤枯渇する（S-2）。
            // 生成時に型で固定する原則のため、ここでは即時確定する（沈黙の誤動作より明示の失敗）。
            if (!profiles.any { managed(it) }) {
                return lastFailure
                    ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "no-managed-profiles"))
            }
            // 制限系あり：失敗ペアを報告して新キーへ
            val pool = pool
            if (pool == null) {
                return lastFailure
                    ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "no-pool"))
            }
            for ((k, scope) in failedScopes) {
                pool.reportQuota(k, scope, (k to scope) in dailyScopes, geminiCooldownSec)
            }
            when (val claimed = pool.acquire(geminiModels())) {
                is AcquireResult.Ready -> {
                    keyIndex = claimed.credentialIndex
                    key = claimed.credential
                    log("🔄 APIキー#${keyIndex + 1} に切り替えました")
                    continue@keyEpoch
                }
                is AcquireResult.Wait -> {
                    log("⏳ レート制限のため ${claimed.waitMillis / 1000}秒待機中...")
                    sleeper(claimed.waitMillis)
                    continue@keyEpoch
                }
                is AcquireResult.Exhausted -> {
                    exhausted = true
                    log("⚠️ 利用可能な全APIキーの上限に達しました")
                    return lastFailure
                        ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "exhausted"))
                }
            }
        }
        return lastFailure ?: LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
    }

    override suspend fun release() {
        pool?.release(keyIndex)
    }
}
