package com.example.novelscraper.translation.common.ingest

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 均一仮説採点器。設計原則:
 * - 厳格脱落の概念を廃止し、全仮説を寛容復号して同一土俵で競わせる
 *   (poison byte による正解脱落＝旧 Tier 構造の脆弱性を構造消滅させる)。
 * - 採点 (block 重み) と採否 (言語 gate) を分離する。重み差 (25対4) では
 *   盗みと救済を区別できないため、採否は言語 gate が担う。
 * - gate を通った仮説同士はどちらで復号しても大外ししない (ASCII 共有・
 *   損失なし)。勝敗は margin rule で決め、僅差は隔離 (fail-closed)。
 *
 * 較正根拠は docs/ingest_calibration.md。各定数の余裕度は測定済み。
 */
object HypothesisScorer {

    // block 重み (同一スケールで全仮説共通)
    private const val W_HANGUL = 25
    private const val W_CJK = 20
    private const val W_KANA = 100
    private const val W_SINGLE_SCRIPT = 4
    private const val W_LATIN_EXT = 4
    private const val W_COMBINING = 4
    private const val W_ASCII = 1
    private const val W_FFFD = 100
    private const val W_CONTROL = 100

    // gate (全て実測較正)
    const val VALID_FFFD_RATIO = 0.05
    const val TOLERANT_FFFD_ABS = 8
    const val TOLERANT_FFFD_RATIO = 0.001
    const val TOP_COVERAGE_MIN = 0.15
    const val KANA_RATIO_MIN = 0.05
    const val EN_ASCII_MIN = 0.50
    const val EN_LATIN_EXT_MAX = 0.30
    const val SINGLE_SCRIPT_MIN = 0.10
    const val MARGIN = 1.2
    const val MIN_EVIDENCE_ABS = 50

    /** JVM名の差異を吸収する正規別名表 (31J漏れの構造的防止)。解決不能時は仮説自体が不参加。 */
    private fun resolve(id: HypothesisId): Charset? {
        val names = when (id) {
            HypothesisId.CP949 -> arrayOf("x-windows-949", "MS949", "EUC-KR")
            HypothesisId.GB18030 -> arrayOf("GB18030", "GBK")
            HypothesisId.BIG5 -> arrayOf("Big5")
            HypothesisId.SJIS -> arrayOf("Windows-31J", "Shift_JIS")
            HypothesisId.EUC_JP -> arrayOf("EUC-JP")
            HypothesisId.W1252 -> arrayOf("windows-1252")
            HypothesisId.W1254 -> arrayOf("windows-1254")
            HypothesisId.W1258 -> arrayOf("windows-1258")
            HypothesisId.W1251 -> arrayOf("windows-1251")
            HypothesisId.W1253 -> arrayOf("windows-1253")
            HypothesisId.W1255 -> arrayOf("windows-1255")
            HypothesisId.W1256 -> arrayOf("windows-1256")
            HypothesisId.TIS620 -> arrayOf("TIS-620", "windows-874")
            HypothesisId.I2022JP -> arrayOf("ISO-2022-JP")
            HypothesisId.I2022KR -> arrayOf("ISO-2022-KR")
            HypothesisId.I2022CN -> arrayOf("ISO-2022-CN")
            else -> return null
        }
        for (name in names) {
            try {
                return Charset.forName(name)
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun resolveCharset(id: HypothesisId): Charset? = when (id) {
        HypothesisId.UTF8 -> StandardCharsets.UTF_8
        else -> resolve(id)
    }

    data class ScriptCounts(
        var ascii: Int = 0,
        var hangul: Int = 0,
        var hirakata: Int = 0,
        var cjk: Int = 0,
        var latinExt: Int = 0,
        var combining: Int = 0,
        var cyrillic: Int = 0,
        var arabic: Int = 0,
        var hebrew: Int = 0,
        var greek: Int = 0,
        var thai: Int = 0,
        var fffd: Int = 0,
        var control: Int = 0
    )

    /** 範囲は \u escapes で記述する (可読性より符号化破損の防止を優先)。 */
    fun countScripts(text: String): ScriptCounts {
        val c = ScriptCounts()
        for (ch in text) {
            when {
                ch in ' '..'~' || ch == '\n' || ch == '\r' || ch == '\t' -> c.ascii++
                ch in '가'..'힣' -> c.hangul++ // U+AC00..U+D7A3
                ch in 'ぁ'..'ヿ' -> c.hirakata++ // U+3041..U+30FF
                ch in '一'..'鿿' -> c.cjk++ // U+4E00..U+9FFF
                ch in ' '..'ɏ' || ch in 'Ḁ'..'ỿ' -> c.latinExt++ // U+00A0..U+024F, U+1E00..U+1EFF
                ch in '̀'..'ͯ' -> c.combining++ // U+0300..U+036F
                ch in 'Ѐ'..'ӿ' -> c.cyrillic++ // U+0400..U+04FF
                ch in '؀'..'ۿ' -> c.arabic++ // U+0600..U+06FF
                ch in '֐'..'׿' -> c.hebrew++ // U+0590..U+05FF
                ch in 'Ͱ'..'Ͽ' -> c.greek++ // U+0370..U+03FF
                ch in 'ก'..'࿿' -> c.thai++ // U+0E01..U+0FFF Thai+Lao (verified codepoints)
                ch == '�' -> c.fffd++ // U+FFFD
                ch.isISOControl() -> c.control++
            }
        }
        return c
    }

    fun tolerantDecode(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    fun strictDecode(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    fun isStrictUtf8(bytes: ByteArray): Boolean =
        strictDecode(StandardCharsets.UTF_8, bytes) != null

    data class ScoredHypothesis(
        val id: HypothesisId,
        val charset: Charset,
        val text: String,
        val score: Int,
        val counts: ScriptCounts
    )

    /** 均一式。全仮説に同一式を適用する */
    fun blockScore(s: ScriptCounts): Int {
        return s.hangul * W_HANGUL +
            s.cjk * W_CJK +
            s.hirakata * W_KANA +
            s.cyrillic * W_SINGLE_SCRIPT +
            s.arabic * W_SINGLE_SCRIPT +
            s.hebrew * W_SINGLE_SCRIPT +
            s.greek * W_SINGLE_SCRIPT +
            s.thai * W_SINGLE_SCRIPT +
            s.latinExt * W_LATIN_EXT +
            s.combining * W_COMBINING +
            s.ascii * W_ASCII -
            s.fffd * W_FFFD -
            s.control * W_CONTROL
    }

    private fun minEvidence(len: Int): Int = minOf(MIN_EVIDENCE_ABS, maxOf(10, len / 4))

    /** 韓国語証拠: ハングル量＋常用音節被覆率 (較正: 陽性43%／陰性最大10%) */
    fun koreanEvidence(text: String, s: ScriptCounts): Boolean {
        if (s.hangul < minEvidence(text.length)) return false
        var top = 0
        for (ch in text) {
            if (ch in '가'..'힣' && ch in LanguageModels.KO_TOP_SYLLABLES) top++
        }
        return top.toDouble() / s.hangul >= TOP_COVERAGE_MIN
    }

    /** 中国語証拠: 漢字量＋常用漢字被覆率 (較正: 陽性56-69%／陰性最大10.7%) */
    fun chineseEvidence(text: String, s: ScriptCounts): Boolean {
        if (s.cjk < minEvidence(text.length)) return false
        var top = 0
        for (ch in text) {
            if (ch in '一'..'鿿' && ch in LanguageModels.ZH_TOP_HANZI) top++
        }
        return top.toDouble() / s.cjk >= TOP_COVERAGE_MIN
    }

    /** 日本語証拠: かな比率 (較正: 陽性47-59%／陰性0%) または機能語 */
    fun japaneseEvidence(text: String, s: ScriptCounts): Boolean {
        if (text.isEmpty()) return false
        if (s.hirakata.toDouble() / text.length >= KANA_RATIO_MIN) return true
        var hits = 0
        var distinct = 0
        for (w in LanguageModels.JA_FUNCTION_WORDS) {
            var c = 0
            var i = text.indexOf(w)
            while (i >= 0) {
                c++
                i = text.indexOf(w, i + w.length)
            }
            if (c > 0) {
                hits += c
                distinct++
            }
        }
        return hits >= 3 && distinct >= 2
    }

    /**
     * 英語 (分かち書き) 証拠: ASCII 主体＋ラテン過密でないこと。
     * 機能語は要求しない (フランス語等の非英語ラテン文も正当なため)。
     * UTF-8 寛容の安全性は FFFD 予算 (0.1%) が担う
     * (実内容由来の FFFD は 1% を大きく超えるため混入しない)。
     */
    fun englishEvidence(text: String, s: ScriptCounts): Boolean {
        if (text.isEmpty()) return false
        val len = text.length.toDouble()
        if (s.ascii / len < EN_ASCII_MIN) return false
        if (s.latinExt / len > EN_LATIN_EXT_MAX) return false
        return true
    }

    /** 単バイト・ラテン系の証拠 (英語証拠と同一式)。 */
    fun latinEvidence(s: ScriptCounts, len: Int): Boolean {
        if (len == 0) return false
        val d = len.toDouble()
        return s.ascii / d >= EN_ASCII_MIN && s.latinExt / d <= EN_LATIN_EXT_MAX
    }

    private fun singleByteIds(): List<HypothesisId> = listOf(
        HypothesisId.W1252, HypothesisId.W1254, HypothesisId.W1258,
        HypothesisId.W1251, HypothesisId.W1253, HypothesisId.W1255,
        HypothesisId.W1256, HypothesisId.TIS620
    )

    private fun isSingleByteId(id: HypothesisId): Boolean = id in singleByteIds()

    /**
     * 仮説の採否 gate。全て実測較正。
     * - validity: 破損率上限 (poison 1-2Bは通過、破損文は脱落)
     * - 言語証拠: 各系統の陽性／陰性分離点
     */
    fun eligible(id: HypothesisId, text: String, s: ScriptCounts): Boolean {
        val len = text.length.toDouble()
        if (len == 0.0) return false
        if (s.fffd / len > VALID_FFFD_RATIO) return false
        return when (id) {
            HypothesisId.CP949 -> koreanEvidence(text, s)
            HypothesisId.GB18030, HypothesisId.BIG5 -> chineseEvidence(text, s)
            HypothesisId.SJIS, HypothesisId.EUC_JP -> japaneseEvidence(text, s)
            HypothesisId.W1252, HypothesisId.W1254, HypothesisId.W1258 ->
                latinEvidence(s, text.length)
            HypothesisId.W1251 -> s.cyrillic / len >= SINGLE_SCRIPT_MIN
            HypothesisId.W1253 -> s.greek / len >= SINGLE_SCRIPT_MIN
            HypothesisId.W1255 -> s.hebrew / len >= SINGLE_SCRIPT_MIN
            HypothesisId.W1256 -> s.arabic / len >= SINGLE_SCRIPT_MIN
            HypothesisId.TIS620 -> s.thai / len >= SINGLE_SCRIPT_MIN
            else -> false
        }
    }

    /** UTF-8寛容仮説 (UTF-8＋ゴミ用)。FFFD予算＋いずれかの言語証拠が必要。 */
    fun eligibleTolerantUtf8(text: String, s: ScriptCounts): Boolean {
        if (text.isEmpty()) return false
        val budget = maxOf(TOLERANT_FFFD_ABS.toDouble(), text.length * TOLERANT_FFFD_RATIO).toInt()
        if (s.fffd > budget) return false
        return koreanEvidence(text, s) || chineseEvidence(text, s) ||
            japaneseEvidence(text, s) || englishEvidence(text, s)
    }

    /** 全仮説を寛容復号→gate→採点し、採点順の適格リストを返す。 */
    fun rankEligible(bytes: ByteArray): List<ScoredHypothesis> {
        val out = mutableListOf<ScoredHypothesis>()
        val utf8 = tolerantDecode(StandardCharsets.UTF_8, bytes) ?: return out
        val utf8Counts = countScripts(utf8)
        if (eligibleTolerantUtf8(utf8, utf8Counts)) {
            out.add(
                ScoredHypothesis(
                    HypothesisId.UTF8, StandardCharsets.UTF_8, utf8,
                    blockScore(utf8Counts), utf8Counts
                )
            )
        }
        for (id in listOf(
            HypothesisId.CP949, HypothesisId.GB18030, HypothesisId.BIG5,
            HypothesisId.SJIS, HypothesisId.EUC_JP,
            HypothesisId.W1252, HypothesisId.W1254, HypothesisId.W1258,
            HypothesisId.W1251, HypothesisId.W1253, HypothesisId.W1255,
            HypothesisId.W1256, HypothesisId.TIS620
        )
        ) {
            val charset = resolve(id) ?: continue
            val text = tolerantDecode(charset, bytes) ?: continue
            val counts = countScripts(text)
            if (!eligible(id, text, counts)) continue
            val score = blockScore(counts)
            if (score <= 0) continue
            out.add(ScoredHypothesis(id, charset, text, score, counts))
        }
        return out.sortedByDescending { it.score }
    }

    /** 単バイト族内の識別。実測導出のバイト頻度表で競わせ、僅差は落とす。 */
    fun resolveSingleByteFamily(
        bytes: ByteArray,
        candidates: List<ScoredHypothesis>
    ): ScoredHypothesis? {
        var first: ScoredHypothesis? = null
        var firstAvg = 0.0
        var secondAvg = 0.0
        for (cand in candidates) {
            val table = LanguageModels.SINGLE_BYTE_TABLES[cand.id] ?: continue
            var sum = 0L
            for (b in bytes) sum += table[b.toInt() and 0xFF]
            val avg = sum.toDouble() / bytes.size.coerceAtLeast(1)
            if (first == null || avg > firstAvg) {
                secondAvg = firstAvg
                firstAvg = avg
                first = cand
            } else if (avg > secondAvg) {
                secondAvg = avg
            }
        }
        if (first != null && firstAvg > 0 && firstAvg >= secondAvg * MARGIN) return first
        return null
    }

    sealed interface Decision {
        data class Adopt(val winner: ScoredHypothesis, val margin: Double) : Decision
        data class Reject(val reason: QuarantineReason, val evidence: String) : Decision
    }

    /**
     * 採択判定。適格0→隔離、1→採択、複数は単バイト族内か否かで分岐し
     * margin 1.2未満は隔離 (fail-closed)。スコア0以下は採らない。
     */
    fun decide(bytes: ByteArray): Decision {
        val ranked = rankEligible(bytes)
        if (ranked.isEmpty()) {
            return Decision.Reject(QuarantineReason.UNDETECTABLE, "no eligible hypothesis")
        }
        if (ranked.size == 1) {
            return Decision.Adopt(ranked[0], Double.POSITIVE_INFINITY)
        }
        if (ranked.all { isSingleByteId(it.id) }) {
            val winner = resolveSingleByteFamily(bytes, ranked)
                ?: return Decision.Reject(
                    QuarantineReason.AMBIGUOUS,
                    "single-byte family margin < $MARGIN: " + ranked.take(2).joinToString { it.id.name }
                )
            return Decision.Adopt(winner, MARGIN)
        }
        val best = ranked[0]
        val runner = ranked[1]
        if (best.score <= 0) {
            return Decision.Reject(QuarantineReason.UNDETECTABLE, "non-positive score")
        }
        val margin = best.score.toDouble() / runner.score.coerceAtLeast(1).toDouble()
        if (margin < MARGIN) {
            return Decision.Reject(
                QuarantineReason.AMBIGUOUS,
                "margin $margin < $MARGIN: ${best.id} vs ${runner.id}"
            )
        }
        return Decision.Adopt(best, margin)
    }
}
