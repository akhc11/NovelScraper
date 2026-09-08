package com.example.novelscraper.translation.v2.infra

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GeminiErrorMapper
import com.example.novelscraper.translation.v2.domain.GenericErrorMapper
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val v2Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal fun v2HttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()

/**
 * エンジン共用のHTTPクライアント（コネクション再利用）。
 * 技術的根拠1行：試行ごとに生成するとプールを使い回せずTLS確立を繰り返すため、OkHttpClientのスレッドセーフ性を活かして共有する。
 */
internal val sharedV2HttpClient: OkHttpClient by lazy { v2HttpClient() }

@Serializable
internal data class V2GeminiPart(val text: String? = null, val thought: Boolean? = null)

@Serializable
internal data class V2GeminiContent(val role: String? = null, val parts: List<V2GeminiPart> = emptyList())

@Serializable
internal data class V2GeminiThinking(val thinkingLevel: String? = null, val thinkingBudget: Int? = null)

@Serializable
internal data class V2GeminiGenConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: V2GeminiThinking? = null,
    val responseMimeType: String? = null,
    val responseSchema: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
internal data class V2GeminiRequest(
    val contents: List<V2GeminiContent>,
    val system_instruction: V2GeminiSystemInstruction? = null,
    val generationConfig: V2GeminiGenConfig? = null
)

@Serializable
internal data class V2GeminiSystemInstruction(val parts: List<V2GeminiPart>)

@Serializable
internal data class V2GeminiCandidate(val content: V2GeminiCandidateContent? = null, val finishReason: String? = null)

@Serializable
internal data class V2GeminiCandidateContent(val parts: List<V2GeminiPart>? = null)

@Serializable
internal data class V2GeminiUsage(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)

@Serializable
internal data class V2GeminiResponse(
    val candidates: List<V2GeminiCandidate>? = null,
    val promptFeedback: V2GeminiFeedback? = null,
    val usageMetadata: V2GeminiUsage? = null
)

@Serializable
internal data class V2GeminiFeedback(val blockReason: String? = null)

/** 送信は解決済み値をそのまま送る（可否判断は能力層の責務）。本構造にない項目（topP等）は送出対象外。level/budget併存時はlevel優先 */
internal fun buildGeminiBody(req: LlmRequest): String {
    val thinking = when {
        !req.options.thinkingLevel.isNullOrBlank() ->
            V2GeminiThinking(thinkingLevel = req.options.thinkingLevel)
        req.options.thinkingBudget != null ->
            V2GeminiThinking(thinkingBudget = req.options.thinkingBudget)
        else -> null
    }
    val schemaElement = when (val s = req.options.jsonSchema) {
        null -> null
        "batch" -> try {
            v2Json.parseToJsonElement(com.example.novelscraper.translation.v2.pipeline.buildBatchJsonSchema())
        } catch (_: Exception) {
            null
        }
        else -> try {
            v2Json.parseToJsonElement(s)
        } catch (_: Exception) {
            null
        }
    }
    val genConfig = if (req.options.temperature != null || req.options.maxOutputTokens != null ||
        thinking != null || schemaElement != null
    ) {
        V2GeminiGenConfig(
            temperature = req.options.temperature,
            maxOutputTokens = req.options.maxOutputTokens,
            thinkingConfig = thinking,
            responseMimeType = if (schemaElement != null) "application/json" else null,
            responseSchema = schemaElement
        )
    } else null
    return v2Json.encodeToString(
        V2GeminiRequest.serializer(),
        V2GeminiRequest(
            contents = listOf(V2GeminiContent(role = "user", parts = listOf(V2GeminiPart(text = req.userText)))),
            system_instruction = V2GeminiSystemInstruction(parts = listOf(V2GeminiPart(text = req.systemPrompt))),
            generationConfig = genConfig
        )
    )
}

internal fun parseGeminiRetryDelay(body: String): Long? {
    val match = Regex("""retryDelay["']?\s*:\s*["']?([0-9]+(?:\.[0-9]+)?)s?""", RegexOption.IGNORE_CASE).find(body)
        ?: return null
    val secDouble = match.groupValues[1].toDoubleOrNull() ?: return null
    return kotlin.math.ceil(secDouble).toLong().coerceIn(0L, com.example.novelscraper.translation.v2.domain.TranslationLimits.RETRY_AFTER_MAX_SEC)
}

internal fun parseGeminiResponse(code: Int, body: String, retryAfterSec: Long? = null): LlmResult {
    val effectiveRetryAfter = retryAfterSec ?: parseGeminiRetryDelay(body)
    if (code == 200) {
        val resp = try {
            v2Json.decodeFromString(V2GeminiResponse.serializer(), body)
        } catch (_: Exception) {
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "decode-error"))
        }
        val candidate = resp.candidates?.firstOrNull()
        val text = candidate?.content?.parts
            ?.filter { it.thought != true }
            ?.mapNotNull { it.text }
            ?.joinToString("")
            .orEmpty()
        if (text.isBlank()) {
            val blockReason = resp.promptFeedback?.blockReason
            val finishReason = candidate?.finishReason
            return when {
                blockReason != null ->
                    LlmResult.Failure(ClassifiedFailure(FailureKind.BLOCKED_DETERMINISTIC, note = "block:$blockReason"))
                finishReason == "SAFETY" ->
                    LlmResult.Failure(ClassifiedFailure(FailureKind.BLOCKED_DETERMINISTIC, note = "safety"))
                else ->
                    LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "empty:${finishReason ?: "?"}"))
            }
        }
        val usage = resp.usageMetadata
        return LlmResult.Success(
            text = text,
            promptTokens = usage?.promptTokenCount ?: 0,
            completionTokens = usage?.candidatesTokenCount ?: 0
        )
    }
    if (code == 429) {
        val mapped = GeminiErrorMapper.map(code, body, effectiveRetryAfter)
        return LlmResult.Failure(mapped, statusCode = code)
    }
    val mapped = GenericErrorMapper.map(code, body)
    return LlmResult.Failure(mapped, statusCode = code)
}

class GeminiHandler(
    private val apiKey: String,
    private val endpointBase: String = "https://generativelanguage.googleapis.com/v1beta/models",
    private val client: OkHttpClient = sharedV2HttpClient
) : ProviderHandler {

    override suspend fun call(request: LlmRequest): LlmResult = withContext(Dispatchers.IO) {
        try {
            val url = "${endpointBase.trimEnd('/')}/${request.model}:generateContent"
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(buildGeminiBody(request).toRequestBody(JSON_MEDIA))
                    .build()
            )
            call.execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                parseGeminiResponse(response.code, bodyString, retryAfter)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "io:${e.message}"))
        }
    }
}
