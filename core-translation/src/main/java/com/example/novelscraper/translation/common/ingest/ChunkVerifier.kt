package com.example.novelscraper.translation.common.ingest

/**
 * 塊検証器。分割済みチャンクが文字化けでないことを3層で検査する。
 * 検出器とは独立した防御網 (defense in depth)。理由を返す (null＝正常)。
 *
 * - FFFD層: 置換文字の洪水
 * - 確定層: 採用charsetが原理的に出せない文字種の混入
 *   (デコーダ仕様上、正規復号では起こり得ない)
 * - 軟層: 期待文字種の欠落とラテン拡張過密。短文・ASCII主体・
 *   結合文字ありは除外し、誤スキップ (作品喪失) より見逃し側に倒す
 *
 * 範囲リテラルは \u escapes で記述する (可読性より符号化破損の防止を優先)。
 */
object ChunkVerifier {

    const val FFFD_RATIO_THRESHOLD = 0.01
    const val FFFD_MIN_COUNT = 5
    const val MIN_SCRIPT_LEN = 100
    const val EXPECTED_SCRIPT_MIN_RATIO = 0.01
    const val EXPECTED_CJK_MIN_RATIO = 0.05
    const val ASCII_DOMINANT_RATIO = 0.80
    const val LATIN_EXT_DENSE_RATIO = 0.30
    const val LATIN_MIN_ASCII_RATIO = 0.50

    fun verify(text: String, expected: HypothesisId): String? {
        if (text.isEmpty()) return null
        val fffd = text.count { it == '�' }
        if (fffd >= FFFD_MIN_COUNT && fffd.toDouble() / text.length > FFFD_RATIO_THRESHOLD) {
            return "FFFD=$fffd/${text.length}"
        }
        if (expected == HypothesisId.UTF8) return null
        val s = HypothesisScorer.countScripts(text)
        val impossible = when (expected) {
            // 単バイト復号は U+0100 以上を出せない
            HypothesisId.W1252, HypothesisId.W1254, HypothesisId.W1258,
            HypothesisId.W1251, HypothesisId.W1253, HypothesisId.W1255,
            HypothesisId.W1256, HypothesisId.TIS620 ->
                s.hangul + s.hirakata + s.cjk > 0
            // JIS X 0208 / KS X 1001 / GB 系に存在しない文字種
            HypothesisId.SJIS, HypothesisId.EUC_JP, HypothesisId.I2022JP ->
                s.hangul > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            HypothesisId.CP949, HypothesisId.I2022KR ->
                s.hirakata > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            HypothesisId.GB18030, HypothesisId.BIG5, HypothesisId.I2022CN ->
                s.hirakata > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            else -> false
        }
        if (impossible) return "impossible-script for $expected"
        if (text.length < MIN_SCRIPT_LEN) return null
        val len = text.length.toDouble()
        if (s.ascii / len < ASCII_DOMINANT_RATIO) {
            val missing = when (expected) {
                // 漢字混じり (漢文引用等) は正当のため cjk でも救う
                HypothesisId.CP949, HypothesisId.I2022KR ->
                    s.hangul / len < EXPECTED_SCRIPT_MIN_RATIO &&
                        s.cjk / len < EXPECTED_CJK_MIN_RATIO
                HypothesisId.SJIS, HypothesisId.EUC_JP, HypothesisId.I2022JP ->
                    s.hirakata / len < EXPECTED_SCRIPT_MIN_RATIO &&
                        s.cjk / len < EXPECTED_CJK_MIN_RATIO
                HypothesisId.GB18030, HypothesisId.BIG5, HypothesisId.I2022CN ->
                    s.cjk / len < EXPECTED_CJK_MIN_RATIO &&
                        s.hangul / len < EXPECTED_SCRIPT_MIN_RATIO
                else -> false
            }
            if (missing) return "missing-expected-script for $expected"
        }
        if ((expected == HypothesisId.W1252 || expected == HypothesisId.W1254 ||
                expected == HypothesisId.W1258) && s.combining == 0 &&
            s.latinExt / len > LATIN_EXT_DENSE_RATIO &&
            s.ascii / len < LATIN_MIN_ASCII_RATIO
        ) {
            return "latin-gibberish for $expected"
        }
        return null
    }
}
