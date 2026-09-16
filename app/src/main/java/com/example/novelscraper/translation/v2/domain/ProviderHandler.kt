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

/**
 * 層間の口（Ports）。中心部は実装ではなくこの口だけに依存する。
 * 技術的根拠1行：外側（OkHttp・SAF・Android枠組み）の差し替えを呼出側の配線だけで済ませ、中心部の書換えを不要にする。
 */
/** 中断可能な待機の口。実体は巡回器への sleeper 注入で満たす。 */
typealias Sleeper = suspend (Long) -> Unit

/** 進捗通知の口。実体はエンジンの状態反映コールバックで満たす。 */
typealias ProgressObserver = (done: Int, total: Int, fileName: String) -> Unit
