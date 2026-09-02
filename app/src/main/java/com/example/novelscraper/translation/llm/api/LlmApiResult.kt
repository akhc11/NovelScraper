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

    /** HTTP 429 Quota制限 */
    data class QuotaExceeded(
        val message: String,
        val retryAfterSec: Int = 60
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

    /** 致命的エラー (認証失敗 401/403、モデル非存在 404 等) */
    data class FatalError(
        val statusCode: Int,
        val message: String
    ) : LlmApiResult()
}