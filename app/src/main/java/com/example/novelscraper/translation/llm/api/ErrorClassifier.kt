package com.example.novelscraper.translation.llm.api

/**
 * HTTPステータス＋本文から次の一手を決めるための分類器（pure・副作用なし）。
 *
 * - CONFIG: 設定修正まで何度やっても直らない（404モデルなし・401/403キー不良・402残高不足等）。
 *   即座の再試行・キー回しをせず、設定確認の案内へ回す。
 * - QUOTA: 429。分間系は待機再試行、日次系は切替。日次判定は ApiKeyPoolManager 側と併用する。
 * - TRANSIENT: 5xx。指数バックオフ再試行。
 * - FATAL: 400要求不良・その他。プロンプト/モデル切替（従来通り）。
 */
enum class ApiFailureKind {
    CONFIG,
    QUOTA,
    TRANSIENT,
    FATAL
}

/** CONFIG系の内訳（UI案内・ログ用） */
enum class ConfigErrorKind {
    MODEL_NOT_FOUND,
    AUTH_FAILED,
    PAYMENT_REQUIRED,
    UNKNOWN;

    /** ユーザー向けの対処案内 */
    fun guidance(): String = when (this) {
        MODEL_NOT_FOUND -> "モデルIDを確認してください（廃止・改名の可能性）"
        AUTH_FAILED -> "APIキーを確認してください"
        PAYMENT_REQUIRED -> "残高・課金状態を確認してください"
        UNKNOWN -> "設定を確認してください"
    }
}

data class ClassifiedHttp(
    val kind: ApiFailureKind,
    val configKind: ConfigErrorKind = ConfigErrorKind.UNKNOWN,
    val note: String = ""
)

object ErrorClassifier {

    /**
     * HTTPステータスコードと応答本文から失敗種別を判定する。
     * 本文が空・壊れていてもステータスだけで安全側に倒す（例外なし）。
     */
    fun classifyHttpStatus(code: Int, body: String): ClassifiedHttp {
        return try {
            classifyInternal(code, body.lowercase())
        } catch (_: Exception) {
            ClassifiedHttp(ApiFailureKind.FATAL, note = "classify-fallback:$code")
        }
    }

    private fun classifyInternal(code: Int, lowerBody: String): ClassifiedHttp {
        return when (code) {
            429 -> ClassifiedHttp(ApiFailureKind.QUOTA, note = "429")
            401, 403 -> ClassifiedHttp(
                ApiFailureKind.CONFIG,
                ConfigErrorKind.AUTH_FAILED,
                note = "$code"
            )
            402 -> ClassifiedHttp(
                ApiFailureKind.CONFIG,
                ConfigErrorKind.PAYMENT_REQUIRED,
                note = "$code"
            )
            404, 410 -> ClassifiedHttp(
                ApiFailureKind.CONFIG,
                ConfigErrorKind.MODEL_NOT_FOUND,
                note = "$code"
            )
            400 -> classifyBadRequest(lowerBody)
            500, 502, 503, 504 -> ClassifiedHttp(ApiFailureKind.TRANSIENT, note = "$code")
            else -> ClassifiedHttp(ApiFailureKind.FATAL, note = "$code")
        }
    }

    /**
     * 400は「直せば通る要求不良」と「設定・契約起因」を分ける。
     * 後者は待っても直らないためCONFIG扱いにする。
     */
    private fun classifyBadRequest(lowerBody: String): ClassifiedHttp {
        return when {
            lowerBody.contains("failed_precondition") ||
                lowerBody.contains("bill") ||
                lowerBody.contains("not available in your country") ||
                lowerBody.contains("free tier is not available") -> ClassifiedHttp(
                ApiFailureKind.CONFIG,
                ConfigErrorKind.PAYMENT_REQUIRED,
                note = "400-precondition"
            )
            lowerBody.contains("api_key_invalid") ||
                lowerBody.contains("api key not valid") ||
                lowerBody.contains("invalid api key") ||
                lowerBody.contains("incorrect api key") ||
                lowerBody.contains("unauthorized") ||
                lowerBody.contains("permission_denied") ||
                lowerBody.contains("forbidden") -> ClassifiedHttp(
                ApiFailureKind.CONFIG,
                ConfigErrorKind.AUTH_FAILED,
                note = "400-auth"
            )
            lowerBody.contains("model_not_found") ||
                lowerBody.contains("does not exist") ||
                lowerBody.contains("no endpoints found") ||
                lowerBody.contains("not a valid model") -> ClassifiedHttp(
                ApiFailureKind.CONFIG,
                ConfigErrorKind.MODEL_NOT_FOUND,
                note = "400-model"
            )
            else -> ClassifiedHttp(ApiFailureKind.FATAL, note = "400-invalid-argument")
        }
    }
}
