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
    /** パラメータ不正など設定起因の400。内容起因（文脈長超過等）と区別し、.failedを作らない */
    INVALID_PARAM,
    UNKNOWN
}

data class ClassifiedFailure(
    val kind: FailureKind,
    val retryAfterSec: Int? = null,
    val configKind: ConfigKind = ConfigKind.UNKNOWN,
    val note: String = ""
)

/**
 * 失敗分组の単一真実。A-2の教訓：分组が各所に分散すると必ず乖離する。
 * なお attemptDrivers/Rotation の振分けは将来種別の検出のため enum 網羅 when のままとする。
 */
/** 再送不能の確定失敗（再送しても直らない） */
fun FailureKind.isDeterministic(): Boolean =
    this == FailureKind.BLOCKED_DETERMINISTIC || this == FailureKind.CONFIG

/** 制限系（待機後再送の対象。FATALは含まない） */
fun FailureKind.isQuotaLike(): Boolean =
    this == FailureKind.QUOTA_DAILY ||
        this == FailureKind.QUOTA_MINUTE ||
        this == FailureKind.RETRYABLE_AFTER

/** 即時確定（待機再送の対象外） */
fun FailureKind.isTerminal(): Boolean = this == FailureKind.FATAL || isDeterministic()

/**
 * .failed ファイルを作成すべき「ファイル内容起因の確定失敗」か判定（pure）。
 * 技術的根拠1行：通信瞬断・5xx・429等の外的要因による失敗は.failedを作らず未完了保留とする。
 */
fun isDeterministicFailure(kind: FailureKind, note: String = ""): Boolean = when (kind) {
    FailureKind.BLOCKED_DETERMINISTIC -> true
    FailureKind.FATAL -> {
        note.contains("context-length") ||
        note.contains("verify-rejected") ||
        note.contains("marker-missing") ||
        note.contains("size-ratio") ||
        note.contains("kana-floor") ||
        note.contains("line-count") ||
        note.contains("blank") ||
        note.contains("residual") ||
        note.contains("quality-rejected")
    }
    else -> false
}
