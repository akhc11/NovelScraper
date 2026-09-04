package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.api.LlmApiClient.awaitResponse
import com.example.novelscraper.translation.llm.api.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object GeminiApiClient {
    private const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    suspend fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        sourceText: String,
        endpointBase: String = DEFAULT_ENDPOINT,
        temperature: Double? = null,
        thinkingLevel: String? = null,
        thinkingBudget: Int? = null
    ): LlmApiResult = withContext(Dispatchers.IO) {
        try {
            // thinkingBudget と thinkingLevel の排他制御 (Google API仕様: 同時指定は400エラー)
            val thinkingConfig = when {
                thinkingBudget != null -> GeminiThinkingConfig(thinkingBudget = thinkingBudget)
                !thinkingLevel.isNullOrBlank() -> GeminiThinkingConfig(thinkingLevel = thinkingLevel)
                else -> null
            }

            // Gemini 3.x Flash など temperature 非対応モデルでは temperature を送らない
            val genConfig = if (temperature != null || thinkingConfig != null) {
                GeminiGenerationConfig(
                    temperature = temperature,
                    thinkingConfig = thinkingConfig
                )
            } else null

            val reqBodyObj = GeminiRequest(
                systemInstruction = GeminiSystemInstruction(
                    parts = listOf(GeminiPart(text = prompt))
                ),
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = sourceText))
                    )
                ),
                generationConfig = genConfig
            )

            val jsonBody = LlmApiClient.json.encodeToString(GeminiRequest.serializer(), reqBodyObj)
            val url = "${endpointBase.trimEnd('/')}/${model}:generateContent"

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(LlmApiClient.JSON_MEDIA_TYPE))
                .build()

            val call = LlmApiClient.httpClient.newCall(request)
            val (code, bodyString) = call.awaitResponse().use { response ->
                response.code to (response.body?.string() ?: "")
            }

            when (code) {
                200 -> {
                    val geminiResp = LlmApiClient.json.decodeFromString(GeminiResponse.serializer(), bodyString)
                    val text = geminiResp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (text.isNullOrBlank()) {
                        return@withContext LlmApiResult.QualityError("Gemini空応答またはテキスト欠損")
                    }
                    val usage = geminiResp.usageMetadata
                    LlmApiResult.Success(
                        text = text,
                        promptTokens = usage?.promptTokenCount ?: 0,
                        completionTokens = usage?.candidatesTokenCount ?: 0,
                        totalTokens = usage?.totalTokenCount ?: 0
                    )
                }
                429 -> {
                    LlmApiResult.QuotaExceeded(
                        message = "Gemini Quota Exceeded (429): ${bodyString.take(3000)}"
                    )
                }
                500, 502, 503, 504 -> {
                    LlmApiResult.NetworkError(
                        statusCode = code,
                        message = "Gemini Server Error ($code): ${bodyString.take(1000)}"
                    )
                }
                else -> {
                    LlmApiResult.FatalError(
                        statusCode = code,
                        message = "Gemini HTTP $code: ${bodyString.take(1000)}"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmApiResult.NetworkError(
                statusCode = -1,
                message = "Gemini 通信例外: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}