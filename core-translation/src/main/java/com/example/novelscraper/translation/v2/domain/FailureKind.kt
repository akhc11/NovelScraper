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
 * 出力上限による打切りか判定（pure）。
 * 技術的根拠1行：打切りの方言（Gemini=MAX_TOKENS系、OpenRouter=length系＋生値）を1述語にし、両社での判定乖離をなくす。
 */
fun isOutputTruncated(vararg reasons: String?): Boolean = reasons.any { r ->
    r.equals("length", ignoreCase = true) ||
        r.equals("max_tokens", ignoreCase = true) ||
        r.equals("max-tokens", ignoreCase = true)
}

/**
 * 検証不合格理由の単一真実。文字列の散在は判定乖離の温床のため、生成と照合はここを経由する。
 * 技術的根拠1行：理由文字列の二重実装は必ず乖離するため、値自体は従来通り・参照だけ寄せる。
 */
object FailureNotes {
    const val CONTEXT_LENGTH = "context-length"
    const val VERIFY_REJECTED = "verify-rejected"
    const val MARKER_MISSING = "marker-missing"
    const val SIZE_RATIO = "size-ratio"
    const val KANA_FLOOR = "kana-floor"
    const val LINE_COUNT = "line-count"
    const val BLANK = "blank"
    const val RESIDUAL = "residual"
    const val QUALITY_REJECTED = "quality-rejected"
    const val CUTOFF_LENGTH = "cutoff:length"
    const val CUTOFF_MAX_TOKENS = "cutoff:max-tokens"
    /** 注釈した確定訳が訳文に無い（辞書不遵守）。確定旗群に含めないため一時的扱いになる。 */
    const val DICT_MISMATCH = "dict-mismatch"
}

/**
 * .failed ファイルを作成すべき「ファイル内容起因の確定失敗」か判定（pure）。
 * 技術的根拠1行：通信瞬断・5xx・429等の外的要因による失敗は.failedを作らず未完了保留とする。
 */
fun isDeterministicFailure(kind: FailureKind, note: String = ""): Boolean = when (kind) {
    FailureKind.BLOCKED_DETERMINISTIC -> true
    FailureKind.FATAL -> {
        note.contains(FailureNotes.CONTEXT_LENGTH) ||
        note.contains(FailureNotes.VERIFY_REJECTED) ||
        note.contains(FailureNotes.MARKER_MISSING) ||
        note.contains(FailureNotes.SIZE_RATIO) ||
        note.contains(FailureNotes.KANA_FLOOR) ||
        note.contains(FailureNotes.LINE_COUNT) ||
        note.contains(FailureNotes.BLANK) ||
        note.contains(FailureNotes.RESIDUAL) ||
        note.contains(FailureNotes.QUALITY_REJECTED)
    }
    else -> false
}
