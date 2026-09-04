package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.api.LlmApiClient.awaitResponse
import com.example.novelscraper.translation.llm.api.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object OpenAiCompatibleClient {

    suspend fun chatCompletion(
        apiKey: String,
        model: String,
        endpoint: String,
        prompt: String,
        sourceText: String,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        repetitionPenalty: Double? = null,
        providerOrder: List<String>? = null,
        providerAllowFallbacks: Boolean? = null,
        reasoningEffort: String? = null,
        reasoningEnabled: Boolean? = null
    ): LlmApiResult = withContext(Dispatchers.IO) {
        try {
            val provider = if (!providerOrder.isNullOrEmpty()) {
                OpenRouterProvider(
                    order = providerOrder,
                    allowFallbacks = providerAllowFallbacks
                )
            } else null

            val (reasoning, finalReasoningEffort) = reasoningPayload(model, reasoningEffort, reasoningEnabled)

            val reqBodyObj = OpenAiChatRequest(
                model = model,
                messages = listOf(
                    OpenAiMessage(role = "system", content = prompt),
                    OpenAiMessage(role = "user", content = sourceText)
                ),
                temperature = temperature,
                topP = topP,
                topK = topK,
                repetitionPenalty = repetitionPenalty,
                provider = provider,
                reasoning = reasoning,
                reasoningEffort = finalReasoningEffort
            )

            val jsonBody = LlmApiClient.json.encodeToString(OpenAiChatRequest.serializer(), reqBodyObj)

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://github.com/akhc11/NovelScraper")
                .addHeader("X-Title", "NovelScraper2")
                .post(jsonBody.toRequestBody(LlmApiClient.JSON_MEDIA_TYPE))
                .build()

            val call = LlmApiClient.httpClient.newCall(request)
            val (code, bodyString) = call.awaitResponse().use { response ->
                response.code to (response.body?.string() ?: "")
            }

            when (code) {
                200 -> {
                    val openAiResp = LlmApiClient.json.decodeFromString(OpenAiChatResponse.serializer(), bodyString)
                    val text = openAiResp.choices?.firstOrNull()?.message?.content
                    if (text.isNullOrBlank()) {
                        return@withContext LlmApiResult.QualityError("OpenAI/OpenRouter空応答またはテキスト欠損")
                    }
                    val usage = openAiResp.usage
                    LlmApiResult.Success(
                        text = text,
                        promptTokens = usage?.promptTokens ?: 0,
                        completionTokens = usage?.completionTokens ?: 0,
                        totalTokens = usage?.totalTokens ?: 0
                    )
                }
                429 -> {
                    LlmApiResult.QuotaExceeded(
                        message = "Quota Exceeded (429): ${bodyString.take(3000)}"
                    )
                }
                500, 502, 503, 504 -> {
                    LlmApiResult.NetworkError(
                        statusCode = code,
                        message = "Server Error ($code): ${bodyString.take(1000)}"
                    )
                }
                else -> {
                    LlmApiResult.FatalError(
                        statusCode = code,
                        message = "HTTP $code: ${bodyString.take(1000)}"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmApiResult.NetworkError(
                statusCode = -1,
                message = "通信例外: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * モデル別の推論パラメータ組立。
     * Groqのqwen系はトップレベルに reasoning_effort: "none" を送る仕様のため、
     * ベンダー分岐はこの関数内に隔離し、呼び出し側に漏らさない。
     * @return reasoning JSON とトップレベル reasoning_effort のペア
     */
    fun reasoningPayload(
        model: String,
        reasoningEffort: String?,
        reasoningEnabled: Boolean?
    ): Pair<kotlinx.serialization.json.JsonElement?, String?> {
        val reasoning = when {
            reasoningEnabled != null -> {
                LlmApiClient.json.parseToJsonElement("""{"enabled":$reasoningEnabled}""")
            }
            reasoningEffort != null && reasoningEffort != "none" && !model.startsWith("qwen/") -> {
                LlmApiClient.json.parseToJsonElement("""{"effort":"$reasoningEffort"}""")
            }
            else -> null
        }
        val finalReasoningEffort = if (model.startsWith("qwen/")) "none" else reasoningEffort
        return reasoning to finalReasoningEffort
    }
}