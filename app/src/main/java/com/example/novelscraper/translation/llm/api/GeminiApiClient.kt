package com.example.novelscraper.translation.llm.api

import com.example.novelscraper.translation.llm.api.LlmApiClient.awaitResponse
import com.example.novelscraper.translation.llm.api.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.ceil

/**
 * 429 本文の `details` から抽出した枠情報。
 * - quotaIds: QuotaFailure violations の quotaId 一覧（例: GenerateRequestsPerMinutePerProjectPerModel-FreeTier）
 * - models: quotaDimensions.model 一覧（どのモデルの枠か）
 * - retryDelaySec: RetryInfo retryDelay の秒数（切上げ。日次枯渇にも付くため待機可否の判定には使わないこと）
 */
data class GeminiQuotaInfo(
    val quotaIds: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val retryDelaySec: Int? = null
)

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
            val (code, bodyString, retryAfterHeader) = call.awaitResponse().use { response ->
                Triple(
                    response.code,
                    response.body?.string() ?: "",
                    response.header("Retry-After")?.toLongOrNull()
                )
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
                    val quota = parseQuotaInfo(bodyString)
                    val summary = buildString {
                        append("Gemini Quota Exceeded (429)")
                        if (quota.quotaIds.isNotEmpty() || quota.models.isNotEmpty() || quota.retryDelaySec != null) {
                            append(" [quota=")
                            append(quota.quotaIds.joinToString("|").ifBlank { "-" })
                            append(" model=")
                            append(quota.models.joinToString("|").ifBlank { "-" })
                            append(" retryDelay=")
                            append(quota.retryDelaySec?.let { "${it}s" } ?: "-")
                            append("]")
                        }
                        append(": ${bodyString.take(3000)}")
                    }
                    LlmApiResult.QuotaExceeded(
                        message = summary,
                        retryAfterSec = quota.retryDelaySec
                            ?: retryAfterHeader?.coerceIn(1L, 600L)?.toInt()
                            ?: 30
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

    /**
     * 429 応答本文から枠情報を抽出する。壊れた本文では空情報を返す（例外なし）。
     */
    fun parseQuotaInfo(body: String): GeminiQuotaInfo {
        return try {
            val error = LlmApiClient.json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?: return GeminiQuotaInfo()
            val details = error["details"]?.jsonArray ?: return GeminiQuotaInfo()
            val quotaIds = linkedSetOf<String>()
            val models = linkedSetOf<String>()
            var retryDelaySec: Int? = null
            for (entry in details) {
                val obj = try {
                    entry.jsonObject
                } catch (_: Exception) {
                    continue
                }
                val type = obj["@type"]?.jsonPrimitive?.contentOrNull ?: continue
                when {
                    type.endsWith("QuotaFailure") -> {
                        for (violation in obj["violations"]?.jsonArray ?: continue) {
                            val v = try {
                                violation.jsonObject
                            } catch (_: Exception) {
                                continue
                            }
                            v["quotaId"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }?.let(quotaIds::add)
                            v["quotaDimensions"]?.jsonObject?.get("model")
                                ?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }?.let(models::add)
                        }
                    }
                    type.endsWith("RetryInfo") -> {
                        if (retryDelaySec == null) {
                            retryDelaySec = parseRetryDelaySec(
                                obj["retryDelay"]?.jsonPrimitive?.contentOrNull
                            )
                        }
                    }
                }
            }
            GeminiQuotaInfo(quotaIds.toList(), models.toList(), retryDelaySec)
        } catch (_: Exception) {
            GeminiQuotaInfo()
        }
    }

    /**
     * RetryInfo retryDelay（例: "17s", "17.5963543s", "500ms"）を秒数に切上げ変換する。
     */
    fun parseRetryDelaySec(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return try {
            val t = raw.trim().lowercase()
            val seconds = when {
                t.endsWith("ms") -> (t.removeSuffix("ms").toDoubleOrNull() ?: return null) / 1000.0
                t.endsWith("s") -> t.removeSuffix("s").toDoubleOrNull() ?: return null
                else -> t.toDoubleOrNull() ?: return null
            }
            if (seconds.isNaN() || seconds <= 0.0) return null
            ceil(seconds).toInt()
        } catch (_: Exception) {
            null
        }
    }
}