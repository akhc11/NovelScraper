package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.engine.LlmProvider
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager

/**
 * プロバイダー別API呼び出しの単一実装。
 * `LlmTranslationEngine` と `LargeFileTranslator` の重複コードを一本化し、
 * temperature 修正の片側適用事故を防ぐ。
 * パラメータは nullable のまま透過し、null 時は JSON から省略される
 * (`encodeDefaults = false` + default null のため)。
 */
object LlmRequestRunner {

    suspend fun callForProfile(
        config: LlmTranslationConfig,
        rotationManager: LlmRotationManager,
        profile: ModelProfile,
        prompt: String,
        sourceText: String
    ): LlmApiResult {
        return when (profile.provider) {
            LlmProvider.GEMINI -> {
                val key = if (config.geminiRotationEnabled) {
                    rotationManager.getCurrentKey()
                } else {
                    config.geminiApiKeys.firstOrNull() ?: ""
                }
                GeminiApiClient.generateContent(
                    apiKey = key,
                    model = profile.modelName,
                    prompt = prompt,
                    sourceText = sourceText,
                    temperature = profile.temperature,
                    thinkingLevel = profile.thinkingLevel,
                    thinkingBudget = profile.thinkingBudget
                )
            }
            LlmProvider.OPENROUTER -> {
                OpenAiCompatibleClient.chatCompletion(
                    apiKey = config.openRouterApiKey,
                    model = profile.modelName,
                    endpoint = config.openRouterEndpoint,
                    prompt = prompt,
                    sourceText = sourceText,
                    temperature = profile.temperature,
                    topP = profile.topP,
                    repetitionPenalty = profile.repetitionPenalty,
                    providerOrder = profile.providerOrder,
                    providerAllowFallbacks = profile.providerAllowFallbacks,
                    reasoningEffort = profile.reasoningEffort,
                    reasoningEnabled = profile.reasoningEnabled
                )
            }
            LlmProvider.GROQ -> {
                OpenAiCompatibleClient.chatCompletion(
                    apiKey = config.groqApiKey,
                    model = profile.modelName,
                    endpoint = config.groqEndpoint,
                    prompt = prompt,
                    sourceText = sourceText,
                    temperature = profile.temperature,
                    topP = profile.topP,
                    repetitionPenalty = profile.repetitionPenalty,
                    reasoningEffort = profile.reasoningEffort,
                    reasoningEnabled = profile.reasoningEnabled
                )
            }
        }
    }
}
