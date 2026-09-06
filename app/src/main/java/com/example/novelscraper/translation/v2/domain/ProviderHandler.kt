package com.example.novelscraper.translation.v2.domain

/**
 * 社非依存の要求・応答型。社方言は各 ProviderHandler 実装内に封印すること。
 */
data class LlmRequest(
    val providerId: String,
    val model: String,
    val systemPrompt: String,
    val userText: String,
    val options: RequestOptions = RequestOptions()
)

data class RequestOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val repetitionPenalty: Double? = null,
    val thinkingLevel: String? = null,
    val thinkingBudget: Int? = null,
    val maxOutputTokens: Int? = null,
    val jsonSchema: String? = null
)

sealed interface LlmResult {
    data class Success(
        val text: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0
    ) : LlmResult

    data class Failure(
        val failure: ClassifiedFailure,
        val statusCode: Int = -1
    ) : LlmResult
}

/** 社ハンドラーの最小契約（送受信のみ。巡回・待機・中止は上位層の責務） */
interface ProviderHandler {
    suspend fun call(request: LlmRequest): LlmResult
}
