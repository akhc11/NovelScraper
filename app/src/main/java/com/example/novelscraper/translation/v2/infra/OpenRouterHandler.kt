package com.example.novelscraper.translation.v2.infra

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GenericErrorMapper
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.resolveOpenRouterParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val v2orJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

private val OR_JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

@Serializable
internal data class V2OrMessage(val role: String, val content: String? = null)

@Serializable
internal data class V2OrProvider(val order: List<String>? = null, val allow_fallbacks: Boolean? = null)

@Serializable
internal data class V2OrRequest(
    val model: String,
    val messages: List<V2OrMessage>,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val repetition_penalty: Double? = null,
    val max_tokens: Int? = null,
    val provider: V2OrProvider? = null,
    val reasoning: JsonElement? = null,
    val response_format: JsonElement? = null
)

@Serializable
internal data class V2OrChoiceMessage(val content: String? = null, val reasoning: String? = null)

@Serializable
internal data class V2OrChoice(val message: V2OrChoiceMessage? = null, val finish_reason: String? = null)

@Serializable
internal data class V2OrUsage(val prompt_tokens: Int? = null, val completion_tokens: Int? = null)

@Serializable
internal data class V2OrResponse(
    val choices: List<V2OrChoice>? = null,
    val usage: V2OrUsage? = null
)

/** 送信は解決済み値をそのまま送る（可否判断は能力層の責務）。reasoningはオブジェクト形式に一本化する */
internal fun buildOpenRouterBody(
    req: LlmRequest,
    reasoningEffort: String? = null,
    reasoningEnabled: Boolean? = null,
    providerOrder: List<String> = emptyList(),
    providerAllowFallbacks: Boolean? = null
): String {
    // 技術的根拠1行：旧設定取込の表外値・空白要素を送信直前で正規化し、400級誤爆を未然に防ぐ。
    val resolved = resolveOpenRouterParams(
        reasoningEffort,
        reasoningEnabled,
        providerOrder,
        providerAllowFallbacks
    )
    val reasoning = when {
        resolved.reasoningEnabled != null ->
            JsonObject(mapOf("enabled" to JsonPrimitive(resolved.reasoningEnabled)))
        resolved.reasoningEffort != null ->
            JsonObject(mapOf("effort" to JsonPrimitive(resolved.reasoningEffort)))
        else -> null
    }
    val responseFormat = if (req.options.jsonSchema != null) {
        JsonObject(mapOf("type" to JsonPrimitive("json_object")))
    } else null
    return v2orJson.encodeToString(
        V2OrRequest.serializer(),
        V2OrRequest(
            model = req.model,
            messages = listOf(
                V2OrMessage(role = "system", content = req.systemPrompt),
                V2OrMessage(role = "user", content = req.userText)
            ),
            temperature = req.options.temperature,
            top_p = req.options.topP,
            repetition_penalty = req.options.repetitionPenalty,
            max_tokens = req.options.maxOutputTokens,
            provider = if (resolved.providerOrder.isNotEmpty()) {
                // providerOrderが空の場合、allow_fallbacks単独指定は送出しない
                V2OrProvider(order = resolved.providerOrder, allow_fallbacks = resolved.providerAllowFallbacks)
            } else null,
            reasoning = reasoning,
            response_format = responseFormat
        )
    )
}

internal fun parseOpenRouterResponse(code: Int, body: String, retryAfterSec: Long? = null): LlmResult {
    if (code == 200) {
        val resp = try {
            v2orJson.decodeFromString(V2OrResponse.serializer(), body)
        } catch (_: Exception) {
            return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "decode-error"))
        }
        val choice = resp.choices?.firstOrNull()
        val text = choice?.message?.content
        if (text.isNullOrBlank()) {
            return when {
                choice?.finish_reason == "length" ->
                    LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "cutoff:length"))
                !choice?.message?.reasoning.isNullOrBlank() ->
                    LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "reasoning-only"))
                else ->
                    LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "empty"))
            }
        }
        return LlmResult.Success(
            text = text,
            promptTokens = resp.usage?.prompt_tokens ?: 0,
            completionTokens = resp.usage?.completion_tokens ?: 0
        )
    }
    if (code == 429) {
        return LlmResult.Failure(
            ClassifiedFailure(
                FailureKind.QUOTA_MINUTE,
                retryAfterSec = retryAfterSec?.coerceIn(1L, 600L)?.toInt(),
                note = "429"
            ),
            statusCode = code
        )
    }
    return LlmResult.Failure(GenericErrorMapper.map(code, body), statusCode = code)
}

class OpenRouterHandler(
    private val apiKey: String,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    private val reasoningEffort: String? = null,
    private val reasoningEnabled: Boolean? = null,
    private val providerOrder: List<String> = emptyList(),
    private val providerAllowFallbacks: Boolean? = null,
    private val client: OkHttpClient = sharedV2HttpClient
) : ProviderHandler {

    override suspend fun call(request: LlmRequest): LlmResult = withContext(Dispatchers.IO) {
        try {
            val call = client.newCall(
                Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://github.com/akhc11/NovelScraper")
                    .addHeader("X-Title", "NovelScraper2")
                    .post(
                        buildOpenRouterBody(
                            request,
                            reasoningEffort,
                            reasoningEnabled,
                            providerOrder,
                            providerAllowFallbacks
                        ).toRequestBody(OR_JSON_MEDIA)
                    )
                    .build()
            )
            call.execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                parseOpenRouterResponse(response.code, bodyString, retryAfter)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "io:${e.message}"))
        }
    }
}
