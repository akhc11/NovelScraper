package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.resolveOpenRouterParams
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.infra.GeminiHandler
import com.example.novelscraper.translation.v2.infra.OpenRouterHandler
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings

/**
 * 既定ハンドラー生成の単一実装（内部専用）。
 * 技術的根拠1行：RunEngineとViewModelに分散したwhen分岐を1箇所に寄せ、将来の他社追加時の修正点を集約するため。未知値はOpenRouter扱いに倒す従来仕様を維持する（外部振る舞い不変）。
 */
fun defaultHandlerFor(
    profile: V2ModelProfile,
    key: String,
    settings: V2Settings
): ProviderHandler {
    return when (profile.providerId.toProviderId()) {
        ProviderId.GEMINI -> GeminiHandler(apiKey = key)
        else -> {
            val resolved = resolveOpenRouterParams(profile)
            OpenRouterHandler(
                apiKey = key,
                endpoint = settings.openRouterEndpoint,
                reasoningEffort = resolved.reasoningEffort,
                reasoningEnabled = resolved.reasoningEnabled,
                providerOrder = resolved.providerOrder,
                providerAllowFallbacks = resolved.providerAllowFallbacks
            )
        }
    }
}
