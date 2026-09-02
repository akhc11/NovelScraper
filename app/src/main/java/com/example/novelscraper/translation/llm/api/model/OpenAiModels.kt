package com.example.novelscraper.translation.llm.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null,
    @SerialName("repetition_penalty")
    val repetitionPenalty: Double? = null,
    val provider: OpenRouterProvider? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenRouterProvider(
    val order: List<String>? = null,
    @SerialName("allow_fallbacks")
    val allowFallbacks: Boolean? = null
)

@Serializable
data class OpenAiChatResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice>? = null,
    val usage: OpenAiUsage? = null,
    val error: OpenAiError? = null
)

@Serializable
data class OpenAiChoice(
    val index: Int? = null,
    val message: OpenAiMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

@Serializable
data class OpenAiError(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null
)