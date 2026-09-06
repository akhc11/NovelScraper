package com.example.novelscraper.translation.v2.domain

/**
 * 社非依存の失敗共通分類。パイプラインはこの分類だけを見て次の一手を決める。
 * 社固有の形式差は各 [ErrorMapper] 実装内に封印すること。
 */
enum class FailureKind {
    /** 待機後に再送可（Retry-After付き制限・5xx等） */
    RETRYABLE_AFTER,

    /** 当日枠枯渇（切替対象。復活しない） */
    QUOTA_DAILY,

    /** 一時制限（冷却後に自動復活） */
    QUOTA_MINUTE,

    /** 内容依存の確定失敗（再送無意味。ブロック等） */
    BLOCKED_DETERMINISTIC,

    /** 設定修正まで直らない（モデルなし・認証・課金等） */
    CONFIG,

    /** その他致命的 */
    FATAL
}

/** CONFIG系の内訳（UI案内・ログ用） */
enum class ConfigKind {
    MODEL_NOT_FOUND,
    AUTH_FAILED,
    PAYMENT_REQUIRED,
    UNKNOWN
}

data class ClassifiedFailure(
    val kind: FailureKind,
    val retryAfterSec: Int? = null,
    val configKind: ConfigKind = ConfigKind.UNKNOWN,
    val note: String = ""
)
