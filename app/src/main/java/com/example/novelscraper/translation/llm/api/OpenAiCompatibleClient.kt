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

            val (reasoning, finalReasoningEffort) =
                reasoningPayload(reasoningEffort, reasoningEnabled)

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
            val (code, bodyString, retryAfterHeader) = call.awaitResponse().use { response ->
                Triple(
                    response.code,
                    response.body?.string() ?: "",
                    response.header("Retry-After")?.toLongOrNull()
                )
            }

            when (code) {
                200 -> {
                    val openAiResp = LlmApiClient.json.decodeFromString(OpenAiChatResponse.serializer(), bodyString)
                    val choice = openAiResp.choices?.firstOrNull()
                    val msg = choice?.message
                    val text = msg?.content
                    if (text.isNullOrBlank()) {
                        if (choice?.finishReason == "length") {
                            return@withContext LlmApiResult.QualityError("OpenAI/OpenRouter応答が途中で切断 (finish_reason=length)")
                        }
                        if (!msg?.reasoning.isNullOrBlank()) {
                            // 推論過程のみで翻訳本文なし（推論文を訳文に混ぜない）
                            return@withContext LlmApiResult.QualityError("OpenAI/OpenRouter推論のみ応答 (翻訳本文なし)")
                        }
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
                        message = "Quota Exceeded (429): ${bodyString.take(3000)}",
                        retryAfterSec = retryAfterHeader?.coerceIn(1L, 600L)?.toInt() ?: -1
                    )
                }
                500, 502, 503, 504 -> {
                    LlmApiResult.NetworkError(
                        statusCode = code,
                        message = "Server Error ($code): ${bodyString.take(1000)}"
                    )
                }
                else -> {
                    mapHttpError(code, bodyString)
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
     * HTTPエラーコード＋本文から次の一手を決める（pure・テスト容易）。
     * 402/404等の設定不良は ConfigError（再試行・キー回しをせず設定案内へ）。
     */
    fun mapHttpError(code: Int, body: String): LlmApiResult {
        val classified = ErrorClassifier.classifyHttpStatus(code, body)
        return when (classified.kind) {
            ApiFailureKind.CONFIG -> LlmApiResult.ConfigError(
                message = "OpenAI互換API設定エラー (${classified.configKind}): ${body.take(500)}",
                kind = classified.configKind
            )
            ApiFailureKind.TRANSIENT -> LlmApiResult.NetworkError(
                statusCode = code,
                message = "Server Error ($code): ${body.take(1000)}"
            )
            else -> LlmApiResult.FatalError(
                statusCode = code,
                message = "HTTP $code: ${body.take(1000)}"
            )
        }
    }

    /**
     * 推論パラメータ組立 (OpenRouter用)。
     * @return reasoning JSON とトップレベル reasoning_effort のペア
     */
    fun reasoningPayload(
        reasoningEffort: String?,
        reasoningEnabled: Boolean?
    ): Pair<kotlinx.serialization.json.JsonElement?, String?> {
        val reasoning = when {
            reasoningEnabled != null -> {
                LlmApiClient.json.parseToJsonElement("""{"enabled":$reasoningEnabled}""")
            }
            reasoningEffort != null && reasoningEffort != "none" -> {
                LlmApiClient.json.parseToJsonElement("""{"effort":"$reasoningEffort"}""")
            }
            else -> null
        }
        return reasoning to reasoningEffort
    }
}