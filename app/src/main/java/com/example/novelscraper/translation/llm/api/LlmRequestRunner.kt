package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * プロバイダー別API呼び出しの単一実装 ＆ 全ワーカー共通リクエスト送信ゲート（交通整理）。
 *
 * 1. `LlmTranslationEngine` と `LargeFileTranslator` の重複コードを一本化し、
 *    temperature 修正の片側適用事故を防ぐ。
 * 2. 全ワーカー共通の送信間隔ゲート（最低10秒）により、複数ワーカーの同時リクエスト集中
 *    （HTTP 503過負荷）を低減する。Google側の容量不足による503自体は防げないため、
 *    呼び出し側で [LlmRetryPolicy] による指数バックオフ再試行と併用すること。
 *    （辞書生成は短文出力のためバイパス、本文翻訳全ワーカーがこのゲートを共有する）
 * 3. 待機は Mutex 内で直前送信からの差分のみミリ秒単位でサスペンドし、API推論処理自体はロック外で完全並行実行される。
 * 4. パラメータは nullable のまま透過し、null 時は JSON から省略される
 *    (`encodeDefaults = false` + default null のため)。
 */
object LlmRequestRunner {

    /** 全経路共通の最低送信間隔 (10秒)。自起因バーストによる503低減のための下限。 */
    const val GATE_MIN_MS = 10000L

    private val throttleMutex = Mutex()
    private var lastRequestTimestamp = 0L

    /**
     * 全ワーカー共通の送信ゲート。直前のリクエストから最低指定秒数（既定10秒）経過するまでサスペンド。
     */
    suspend fun acquireGate(minIntervalMs: Long = GATE_MIN_MS) {
        throttleMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTimestamp
            if (lastRequestTimestamp > 0L && elapsed < minIntervalMs) {
                val waitMs = minIntervalMs - elapsed
                if (currentCoroutineContext().isActive) {
                    delay(waitMs)
                }
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }
    }

    /**
     * 全ワーカー共通の送信ゲート。直前のリクエストから最低指定秒数（デフォルト10秒、最低10秒）経過するまでサスペンド。
     */
    private suspend fun throttle(config: LlmTranslationConfig) {
        acquireGate(maxOf(config.requestDelaySec * 1000L, GATE_MIN_MS))
    }

    suspend fun callForProfile(
        config: LlmTranslationConfig,
        rotationManager: LlmRotationManager,
        profile: ModelProfile,
        prompt: String,
        sourceText: String,
        responseMimeType: String? = null,
        responseSchema: kotlinx.serialization.json.JsonElement? = null
    ): LlmApiResult {
        throttle(config)

        // プロバイダー差異は handler に委譲し、ここに分岐を持たない
        return handlerFor(profile.provider).call(
            config = config,
            rotationManager = rotationManager,
            profile = profile,
            prompt = prompt,
            sourceText = sourceText,
            responseMimeType = responseMimeType,
            responseSchema = responseSchema
        )
    }
}
