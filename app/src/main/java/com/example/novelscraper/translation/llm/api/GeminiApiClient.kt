package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.api.LlmApiClient.awaitResponse
import com.example.novelscraper.translation.llm.api.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
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
        thinkingBudget: Int? = null,
        maxOutputTokens: Int? = 65536,
        responseMimeType: String? = null,
        responseSchema: JsonElement? = null
    ): LlmApiResult = withContext(Dispatchers.IO) {
        try {
            // thinkingBudget と thinkingLevel の排他制御 (Google API仕様: 同時指定は400エラー)
            val thinkingConfig = when {
                thinkingBudget != null -> GeminiThinkingConfig(thinkingBudget = thinkingBudget)
                !thinkingLevel.isNullOrBlank() -> GeminiThinkingConfig(thinkingLevel = thinkingLevel)
                else -> null
            }

            // Gemini 3.x Flash など temperature 非対応モデルでは temperature を送らない
            val genConfig = if (temperature != null || thinkingConfig != null || maxOutputTokens != null ||
                responseMimeType != null || responseSchema != null
            ) {
                GeminiGenerationConfig(
                    temperature = temperature,
                    maxOutputTokens = maxOutputTokens,
                    thinkingConfig = thinkingConfig,
                    responseMimeType = responseMimeType,
                    responseSchema = responseSchema
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
                    val candidate = geminiResp.candidates?.firstOrNull()
                    val nonThoughtParts = candidate?.content?.parts?.filter { it.thought != true } ?: emptyList()
                    val text = nonThoughtParts.mapNotNull { it.text }.joinToString("").ifBlank {
                        // 思考パート単体の場合等のフォールバック
                        candidate?.content?.parts?.mapNotNull { it.text }?.joinToString("") ?: ""
                    }

                    if (text.isBlank()) {
                        val blockReason = geminiResp.promptFeedback?.blockReason
                        val finishReason = candidate?.finishReason
                        val detail = when {
                            blockReason != null -> "プロンプト拒否 ($blockReason)"
                            finishReason == "SAFETY" -> "セーフティ検知 (SAFETY)"
                            finishReason == "MAX_TOKENS" -> "最大トークン上限到達"
                            finishReason != null -> "生成停止 ($finishReason)"
                            else -> "空応答またはテキスト欠損"
                        }
                        return@withContext LlmApiResult.QualityError("Gemini $detail")
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