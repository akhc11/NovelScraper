package com.example.novelscraper.translation.v2.domain

/**
 * 社形式の誤差配器。HTTPステータス＋本文（＋Retry-After）を共通分類へ正規化する。
 * pure・副作用なし。対応表の回帰テスト（V2DomainTest内の分類表）を必須とする。
 */
interface ErrorMapper {
    fun map(code: Int, body: String, retryAfterSec: Long? = null): ClassifiedFailure
}

/**
 * 社共通の既定配器。状態符号で安全側に倒し、400は本文で設定起因と要求不良を分ける。
 * 429は一時制限扱い（日次判別は社別配器の責務）。
 */
object GenericErrorMapper : ErrorMapper {

    override fun map(code: Int, body: String, retryAfterSec: Long?): ClassifiedFailure {
        return try {
            mapInternal(code, body.lowercase(), retryAfterSec)
        } catch (_: Exception) {
            ClassifiedFailure(FailureKind.FATAL, note = "classify-fallback:$code")
        }
    }

    private fun mapInternal(code: Int, lowerBody: String, retryAfterSec: Long?): ClassifiedFailure {
        return when (code) {
            429 -> ClassifiedFailure(
                FailureKind.QUOTA_MINUTE,
                retryAfterSec = retryAfterSec?.coerceIn(
                    TranslationLimits.RETRY_AFTER_MIN_SEC,
                    TranslationLimits.RETRY_AFTER_MAX_SEC
                )?.toInt(),
                note = "429"
            )
            401, 403 -> ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.AUTH_FAILED, note = "$code")
            402 -> ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.PAYMENT_REQUIRED, note = "$code")
            404, 410 -> ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.MODEL_NOT_FOUND, note = "$code")
            400 -> mapBadRequest(lowerBody)
            500, 502, 503, 504 -> ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "$code")
            else -> ClassifiedFailure(FailureKind.FATAL, note = "$code")
        }
    }

    private fun mapBadRequest(lowerBody: String): ClassifiedFailure {
        return when {
            lowerBody.contains("failed_precondition") ||
                lowerBody.contains("bill") ||
                lowerBody.contains("not available in your country") ||
                lowerBody.contains("free tier is not available") ->
                ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.PAYMENT_REQUIRED, note = "400-precondition")
            lowerBody.contains("api_key_invalid") ||
                lowerBody.contains("api key not valid") ||
                lowerBody.contains("invalid api key") ||
                lowerBody.contains("incorrect api key") ||
                lowerBody.contains("unauthorized") ||
                lowerBody.contains("permission_denied") ||
                lowerBody.contains("forbidden") ->
                ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.AUTH_FAILED, note = "400-auth")
            lowerBody.contains("model_not_found") ||
                lowerBody.contains("does not exist") ||
                lowerBody.contains("no endpoints found") ||
                lowerBody.contains("not a valid model") ->
                ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.MODEL_NOT_FOUND, note = "400-model")
            // 技術的根拠1行：文脈長超過はファイル内容依存の確定失敗（.failed対象）。設定起因と混同しない。
            lowerBody.contains("maximum context") ||
                lowerBody.contains("context length") ||
                lowerBody.contains("context_length") ||
                lowerBody.contains("too many tokens") ||
                lowerBody.contains("token limit") ||
                lowerBody.contains("input too long") ||
                lowerBody.contains("prompt is too long") ||
                lowerBody.contains("request too large") ||
                lowerBody.contains("content too long") ->
                ClassifiedFailure(FailureKind.FATAL, note = "400-context-length")
            // 技術的根拠1行：パラメータ不正は設定起因として扱い、.failedを作らず修正後再実行に回す。
            lowerBody.contains("invalid parameter") ||
                lowerBody.contains("invalid_param") ||
                lowerBody.contains("unsupported parameter") ||
                lowerBody.contains("unknown parameter") ||
                lowerBody.contains("additional properties") ||
                lowerBody.contains("validation error") ||
                lowerBody.contains("invalid value") ||
                lowerBody.contains("must be") ||
                lowerBody.contains("should be") ||
                lowerBody.contains("not supported") ||
                lowerBody.contains("not allowed") ||
                lowerBody.contains("mutually exclusive") ||
                lowerBody.contains("response_format") ||
                lowerBody.contains("response_schema") ||
                lowerBody.contains("temperature") ||
                lowerBody.contains("top_p") ||
                lowerBody.contains("top_k") ||
                lowerBody.contains("penalty") ||
                lowerBody.contains("reasoning") ||
                lowerBody.contains("thinking") ||
                lowerBody.contains("effort") ->
                ClassifiedFailure(FailureKind.CONFIG, configKind = ConfigKind.INVALID_PARAM, note = "400-param")
            else -> ClassifiedFailure(FailureKind.FATAL, note = "400-invalid-argument")
        }
    }
}

/**
 * Gemini用配器。429のみ本文のquota IDで日次／分次を分離し、それ以外は共通配器に委譲する。
 */
object GeminiErrorMapper : ErrorMapper {

    override fun map(code: Int, body: String, retryAfterSec: Long?): ClassifiedFailure {
        if (code == 429 && isDailyQuotaExceeded(body)) {
            return ClassifiedFailure(FailureKind.QUOTA_DAILY, note = "429-daily")
        }
        return GenericErrorMapper.map(code, body, retryAfterSec)
    }

    fun isDailyQuotaExceeded(errorMessage: String): Boolean {
        val lower = errorMessage.lowercase()
        return lower.contains("requestsperday") ||
            lower.contains("per day") ||
            lower.contains("perday") ||
            lower.contains("daily limit") ||
            lower.contains("daily quota")
    }
}
