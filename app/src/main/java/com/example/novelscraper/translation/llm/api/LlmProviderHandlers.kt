package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.engine.LlmProvider
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.rotation.LlmRotationManager
import kotlinx.serialization.json.JsonElement

/** 推論パラメータの送り方（モデル名ではなくプロバイダー仕様で決める） */
enum class ReasoningStyle {
    STANDARD,
    TOP_LEVEL_NONE
}

/**
 * プロバイダー差異の単一集約点。
 * 呼び出し側（engine/large）は provider による分岐を持たず、このハンドラーに聞くこと。
 * 新規プロバイダー対応は handlerFor への1行追加＋必要なら新ハンドラーで完結する。
 */
interface LlmProviderHandler {
    val provider: LlmProvider

    /** キーローテーション管理対象か（現状Gemini＋有効時のみtrue） */
    fun managesKeyRotation(config: LlmTranslationConfig): Boolean

    fun reasoningStyle(): ReasoningStyle

    suspend fun call(
        config: LlmTranslationConfig,
        rotationManager: LlmRotationManager,
        profile: ModelProfile,
        prompt: String,
        sourceText: String,
        responseMimeType: String? = null,
        responseSchema: JsonElement? = null
    ): LlmApiResult
}

fun handlerFor(provider: LlmProvider): LlmProviderHandler = when (provider) {
    LlmProvider.GEMINI -> GeminiHandler
    LlmProvider.OPENROUTER -> OpenAiCompatibleHandler(LlmProvider.OPENROUTER)
    LlmProvider.GROQ -> OpenAiCompatibleHandler(LlmProvider.GROQ)
}

object GeminiHandler : LlmProviderHandler {
    override val provider: LlmProvider = LlmProvider.GEMINI

    override fun managesKeyRotation(config: LlmTranslationConfig): Boolean =
        config.geminiRotationEnabled

    override fun reasoningStyle(): ReasoningStyle = ReasoningStyle.STANDARD

    override suspend fun call(
        config: LlmTranslationConfig,
        rotationManager: LlmRotationManager,
        profile: ModelProfile,
        prompt: String,
        sourceText: String,
        responseMimeType: String?,
        responseSchema: JsonElement?
    ): LlmApiResult {
        val key = if (config.geminiRotationEnabled) {
            rotationManager.getCurrentKey()
        } else {
            config.geminiApiKeys.firstOrNull() ?: ""
        }
        return GeminiApiClient.generateContent(
            apiKey = key,
            model = profile.modelName,
            prompt = prompt,
            sourceText = sourceText,
            temperature = profile.temperature,
            thinkingLevel = profile.thinkingLevel,
            thinkingBudget = profile.thinkingBudget,
            responseMimeType = responseMimeType,
            responseSchema = responseSchema
        )
    }
}

class OpenAiCompatibleHandler(override val provider: LlmProvider) : LlmProviderHandler {
    override fun managesKeyRotation(config: LlmTranslationConfig): Boolean = false

    override fun reasoningStyle(): ReasoningStyle =
        if (provider == LlmProvider.GROQ) ReasoningStyle.TOP_LEVEL_NONE else ReasoningStyle.STANDARD

    override suspend fun call(
        config: LlmTranslationConfig,
        rotationManager: LlmRotationManager,
        profile: ModelProfile,
        prompt: String,
        sourceText: String,
        responseMimeType: String?,
        responseSchema: JsonElement?
    ): LlmApiResult {
        val (apiKey, endpoint) = when (provider) {
            LlmProvider.OPENROUTER -> config.openRouterApiKey to config.openRouterEndpoint
            else -> config.groqApiKey to config.groqEndpoint
        }
        return OpenAiCompatibleClient.chatCompletion(
            apiKey = apiKey,
            model = profile.modelName,
            endpoint = endpoint,
            prompt = prompt,
            sourceText = sourceText,
            temperature = profile.temperature,
            topP = profile.topP,
            repetitionPenalty = profile.repetitionPenalty,
            providerOrder = profile.providerOrder,
            providerAllowFallbacks = profile.providerAllowFallbacks,
            reasoningEffort = profile.reasoningEffort,
            reasoningEnabled = profile.reasoningEnabled,
            topLevelReasoningNone = reasoningStyle() == ReasoningStyle.TOP_LEVEL_NONE
        )
    }
}
