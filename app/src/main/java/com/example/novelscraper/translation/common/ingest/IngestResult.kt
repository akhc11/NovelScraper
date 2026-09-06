package com.example.novelscraper.translation.common.ingest

import java.nio.charset.Charset

/**
 * 取込 (Ingest) の結果。fail-closed 契約:
 * 成功以外はテキストを返さない。化けた成果物を残さないことが不変条件。
 */
sealed interface IngestResult {

    /** 成功。可Paired text は検証済み。 */
    data class Success(
        val text: String,
        val provenance: Provenance
    ) : IngestResult

    /** 隔離。理由付きで保存・分割を行わない (可視のスキップ)。 */
    data class Quarantined(
        val reason: QuarantineReason,
        val evidence: String
    ) : IngestResult

    /** 失敗 (I/O 等)。理由付き Failure として扱う */
    data class Failed(
        val cause: Throwable
    ) : IngestResult

    /** 成功時はテキスト、隔離・失敗時は理由付き例外。 */
    fun getOrThrow(): String = when (this) {
        is Success -> text
        is Quarantined -> throw java.io.IOException("quarantined: $reason ($evidence)")
        is Failed -> throw java.io.IOException("ingest failed", cause)
    }
}

/** 採用来歴。デバッグ・隔離理由表示・将来の再較正のための記録。 */
data class Provenance(
    val charset: Charset,
    val canonicalId: HypothesisId,
    val confidence: Int,
    val method: DetectionMethod,
    val fffdCount: Int
)

/** 検出手法。確信度の根拠。 */
enum class DetectionMethod {
    EMPTY,
    BOM,
    STRICT_UTF8,
    DECLARED,
    ESC_2022,
    HYPOTHESIS,
    HYPOTHESIS_TOLERANT
}

/** 隔離理由。ユーザー可視のスキップ理由になる。 */
enum class QuarantineReason {
    TOO_LARGE,
    DECLARED_MISMATCH,
    CORRUPT_ESCAPE,
    UNDETECTABLE,
    AMBIGUOUS
}

/** 仮説の正規ID。JVMの別名差異 (Windows-31J等) を吸収するための正本。 */
enum class HypothesisId {
    UTF8,
    CP949,
    GB18030,
    BIG5,
    SJIS,
    EUC_JP,
    W1252,
    W1254,
    W1258,
    W1251,
    W1253,
    W1255,
    W1256,
    TIS620,
    I2022JP,
    I2022KR,
    I2022CN
}

/** 設定UI用の入力文字コード選択肢 (値 to 表示名)。先頭 AUTO＝自動判定。 */
val ENCODING_OPTIONS: List<Pair<String, String>> = listOf(
    "AUTO" to "自動判定",
    "CP949" to "韓国語 CP949",
    "GB18030" to "中国語 GB18030",
    "BIG5" to "繁体字 Big5",
    "SJIS" to "日本語 Shift_JIS",
    "EUC_JP" to "日本語 EUC-JP",
    "UTF8" to "UTF-8",
    "W1252" to "西欧 windows-1252"
)

/** ユーザー・フォルダ指定路の入力文字コード。 */
enum class DeclaredEncoding {
    CP949,
    GB18030,
    BIG5,
    SJIS,
    EUC_JP,
    UTF8,
    W1252;

    companion object {
        /** 設定値 ("AUTO"・未知文字列は null＝自動判定)。 */
        fun parseOrNull(value: String?): DeclaredEncoding? {
            if (value == null) return null
            return try {
                if (value == "AUTO") null else valueOf(value)
            } catch (_: Exception) {
                null
            }
        }
    }
}
