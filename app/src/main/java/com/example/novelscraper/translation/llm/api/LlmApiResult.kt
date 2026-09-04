package com.example.novelscraper.translation.llm.api

/**
 * LLM API 呼び出しの実行結果
 */
sealed class LlmApiResult {
    data class Success(
        val text: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    ) : LlmApiResult()

    /**
     * HTTP 429 Quota制限。
     * @param retryAfterSec サーバー指定の待機秒数。不明時は -1（呼び出し側で既定待機を使うこと）。
     */
    data class QuotaExceeded(
        val message: String,
        val retryAfterSec: Int = -1
    ) : LlmApiResult()

    /** ネットワークエラー・一時的なサーバー障害 (HTTP 502/503/Timeout等) */
    data class NetworkError(
        val statusCode: Int,
        val message: String
    ) : LlmApiResult()

    /** 空応答・不正なJSON構造・品質NG (自動プロンプトリトライ対象) */
    data class QualityError(
        val reason: String
    ) : LlmApiResult()

    /**
     * 設定修正まで何度やっても直らない確定失敗 (404モデルなし・401/403キー不良・402残高不足等)。
     * 即座の再試行・キー回しをせず、`.failed`も作らず設定確認の案内へ回すこと。
     */
    data class ConfigError(
        val message: String,
        val kind: ConfigErrorKind = ConfigErrorKind.UNKNOWN
    ) : LlmApiResult()

    /** 致命的エラー (認証失敗 401/403、モデル非存在 404 等) */
    data class FatalError(
        val statusCode: Int,
        val message: String
    ) : LlmApiResult()
}