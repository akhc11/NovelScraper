package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.engine.LlmProvider
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class LlmHealthStatus {
    OK,
    RATE_LIMITED,
    CONFIG,
    FAILED,
    TIMEOUT
}

data class LlmHealthResult(
    val status: LlmHealthStatus,
    val detail: String,
    val configKind: ConfigErrorKind = ConfigErrorKind.UNKNOWN
) {
    fun statusText(): String = when (status) {
        LlmHealthStatus.OK -> "✅ 接続OK${detail.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}"
        LlmHealthStatus.RATE_LIMITED -> "⏳ 制限中（$detail）"
        LlmHealthStatus.CONFIG -> "⚙️ ${configKind.guidance()}（$detail）"
        LlmHealthStatus.FAILED -> "❌ $detail"
        LlmHealthStatus.TIMEOUT -> "⏱ タイムアウト（${PING_TIMEOUT_SEC}秒）"
    }

    companion object {
        const val PING_TIMEOUT_SEC = 20
    }
}

/**
 * 設定保存前の疎通テスト。極小プロンプトで1往復し、設定腐敗（404/401/402）を翻訳開始前に検出する。
 * 保存はブロックしないこと（呼び出し側でボタン操作として実行する）。
 * 注意：送信ゲート（10秒）を1回消費するため、直後の翻訳開始が最大10秒遅れる場合がある。
 */
object LlmHealthCheck {
    const val PING_TIMEOUT_MS = LlmHealthResult.PING_TIMEOUT_SEC * 1000L
    const val PING_PROMPT = "Reply with only the two letters: OK"
    const val PING_SOURCE = "ping"

    suspend fun ping(config: LlmTranslationConfig, profile: ModelProfile): LlmHealthResult {
        // 先頭キーで確認する（複数キーの巡回は翻訳実行時に回る）
        val firstKey = when (profile.provider) {
            LlmProvider.GEMINI -> config.geminiApiKeys.firstOrNull { it.isNotBlank() } ?: ""
            LlmProvider.OPENROUTER -> config.openRouterApiKey
        }
        if (firstKey.isBlank()) {
            return LlmHealthResult(
                LlmHealthStatus.CONFIG,
                "APIキー未設定",
                ConfigErrorKind.AUTH_FAILED
            )
        }

        val rotationManager = LlmRotationManager(
            workerId = 0,
            currentKeyIndex = 0,
            currentKey = firstKey,
            profiles = listOf(profile),
            keyPoolManager = null,
            switchCooldownSec = 0
        )

        val result = try {
            withTimeoutOrNull(PING_TIMEOUT_MS) {
                LlmRequestRunner.callForProfile(
                    config = config,
                    rotationManager = rotationManager,
                    profile = profile,
                    prompt = PING_PROMPT,
                    sourceText = PING_SOURCE
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LlmHealthResult(LlmHealthStatus.FAILED, "疎通例外: ${e.message}")
        } ?: return LlmHealthResult(LlmHealthStatus.TIMEOUT, "")

        return when (result) {
            is LlmApiResult.Success -> LlmHealthResult(LlmHealthStatus.OK, "応答あり")
            is LlmApiResult.QuotaExceeded -> LlmHealthResult(
                LlmHealthStatus.RATE_LIMITED,
                result.message.take(120)
            )
            is LlmApiResult.ConfigError -> LlmHealthResult(
                LlmHealthStatus.CONFIG,
                result.message.take(200),
                result.kind
            )
            is LlmApiResult.FatalError -> LlmHealthResult(
                LlmHealthStatus.FAILED,
                "HTTP ${result.statusCode}: ${result.message.take(200)}"
            )
            is LlmApiResult.NetworkError -> LlmHealthResult(
                LlmHealthStatus.FAILED,
                "通信エラー: ${result.message.take(200)}"
            )
            is LlmApiResult.QualityError -> LlmHealthResult(
                LlmHealthStatus.FAILED,
                "応答異常: ${result.reason.take(200)}"
            )
        }
    }
}
