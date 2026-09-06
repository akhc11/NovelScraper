package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * v2取込。バイト列→検証済みテキストの唯一入口（fail-closed）。
 * 判定手順・較正値・頻度表は凍結仕様として旧実装と同一とし、
 * 新旧クロステストで等価を担保する。旧コードの参照・流用なし（本ファイルが正本）。
 *
 * 実行順序：宣言路 → BOM署名路 → ESC署名路 → 厳格UTF-8 → 均一仮説採点。
 * 成功以外はテキストを返さない。呼び元は隔離を理由付きスキップとして扱う。
 */
enum class V2CharsetId {
    UTF8, CP949, GB18030, BIG5, SJIS, EUC_JP,
    W1252, W1254, W1258, W1251, W1253, W1255, W1256, TIS620,
    I2022JP, I2022KR, I2022CN
}

enum class V2DetectMethod {
    EMPTY, BOM, STRICT_UTF8, DECLARED, ESC_2022, HYPOTHESIS, HYPOTHESIS_TOLERANT
}

enum class V2QuarantineReason {
    TOO_LARGE, DECLARED_MISMATCH, CORRUPT_ESCAPE, UNDETECTABLE, AMBIGUOUS
}

data class V2Provenance(
    val charsetName: String,
    val id: V2CharsetId,
    val confidence: Int,
    val method: V2DetectMethod,
    val fffdCount: Int
)

sealed interface V2IngestResult {
    data class Success(val text: String, val provenance: V2Provenance) : V2IngestResult
    data class Quarantined(val reason: V2QuarantineReason, val evidence: String) : V2IngestResult
}

object V2Ingest {

    const val MAX_INGEST_BYTES: Int = 64 * 1024 * 1024

    fun ingest(bytes: ByteArray, declared: V2DeclaredEncoding? = null): V2IngestResult {
        if (bytes.size > MAX_INGEST_BYTES) {
            return V2IngestResult.Quarantined(
                V2QuarantineReason.TOO_LARGE,
                "size > $MAX_INGEST_BYTES bytes"
            )
        }
        if (bytes.isEmpty()) {
            return V2IngestResult.Success(
                "",
                V2Provenance("UTF-8", V2CharsetId.UTF8, 100, V2DetectMethod.EMPTY, 0)
            )
        }
        if (declared != null) return ingestDeclared(bytes, declared)

        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            val text = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            return V2IngestResult.Success(
                text,
                V2Provenance("UTF-8", V2CharsetId.UTF8, 100, V2DetectMethod.BOM, text.count { it == '�' })
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            return V2IngestResult.Success(
                text,
                V2Provenance("UTF-16LE", V2CharsetId.UTF8, 100, V2DetectMethod.BOM, 0)
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            return V2IngestResult.Success(
                text,
                V2Provenance("UTF-16BE", V2CharsetId.UTF8, 100, V2DetectMethod.BOM, 0)
            )
        }

        sniffIso2022(bytes)?.let { id ->
            val charset = V2Scorer.resolveCharset(id) ?: StandardCharsets.UTF_8
            val text = V2Scorer.strictDecode(charset, bytes)
            if (text != null) {
                return V2IngestResult.Success(
                    text,
                    V2Provenance(charset.name(), id, 95, V2DetectMethod.ESC_2022, 0)
                )
            }
            return V2IngestResult.Quarantined(
                V2QuarantineReason.CORRUPT_ESCAPE,
                "ESC signature for $id but strict decode failed"
            )
        }

        val strictUtf8 = V2Scorer.strictDecode(StandardCharsets.UTF_8, bytes)
        if (strictUtf8 != null) {
            return V2IngestResult.Success(
                strictUtf8,
                V2Provenance("UTF-8", V2CharsetId.UTF8, 100, V2DetectMethod.STRICT_UTF8, 0)
            )
        }

        return when (val decision = V2Scorer.decide(bytes)) {
            is V2Scorer.Decision.Adopt -> {
                val w = decision.winner
                val method = if (w.id == V2CharsetId.UTF8) {
                    V2DetectMethod.HYPOTHESIS_TOLERANT
                } else {
                    V2DetectMethod.HYPOTHESIS
                }
                V2IngestResult.Success(
                    w.text,
                    V2Provenance(
                        w.charset.name(), w.id, confidenceOf(decision.margin),
                        method, w.counts.fffd
                    )
                )
            }
            is V2Scorer.Decision.Reject ->
                V2IngestResult.Quarantined(decision.reason, decision.evidence)
        }
    }

    private fun ingestDeclared(bytes: ByteArray, declared: V2DeclaredEncoding): V2IngestResult {
        val id = when (declared) {
            V2DeclaredEncoding.CP949 -> V2CharsetId.CP949
            V2DeclaredEncoding.GB18030 -> V2CharsetId.GB18030
            V2DeclaredEncoding.BIG5 -> V2CharsetId.BIG5
            V2DeclaredEncoding.SJIS -> V2CharsetId.SJIS
            V2DeclaredEncoding.EUC_JP -> V2CharsetId.EUC_JP
            V2DeclaredEncoding.UTF8 -> V2CharsetId.UTF8
            V2DeclaredEncoding.W1252 -> V2CharsetId.W1252
        }
        val charset = V2Scorer.resolveCharset(id) ?: StandardCharsets.UTF_8
        if (id == V2CharsetId.UTF8) {
            val strict = V2Scorer.strictDecode(charset, bytes)
            if (strict != null) {
                return V2IngestResult.Success(
                    strict,
                    V2Provenance(charset.name(), id, 95, V2DetectMethod.DECLARED, 0)
                )
            }
        }
        val text = V2Scorer.tolerantDecode(charset, bytes)
            ?: return V2IngestResult.Quarantined(
                V2QuarantineReason.DECLARED_MISMATCH,
                "declared $declared undecodable"
            )
        val fffd = text.count { it == '�' }
        val budget = maxOf(
            V2Scorer.TOLERANT_FFFD_ABS.toDouble(),
            text.length * V2Scorer.TOLERANT_FFFD_RATIO
        ).toInt()
        if (fffd > budget) {
            return V2IngestResult.Quarantined(
                V2QuarantineReason.DECLARED_MISMATCH,
                "declared $declared fffd=$fffd/${text.length} over budget=$budget"
            )
        }
        val counts = V2Scorer.countScripts(text)
        val evidenced = when (id) {
            V2CharsetId.CP949 -> V2Scorer.koreanEvidence(text, counts)
            V2CharsetId.GB18030, V2CharsetId.BIG5 -> V2Scorer.chineseEvidence(text, counts)
            V2CharsetId.SJIS, V2CharsetId.EUC_JP -> V2Scorer.japaneseEvidence(text, counts)
            V2CharsetId.W1252 -> V2Scorer.latinEvidence(counts, text.length)
            V2CharsetId.UTF8 -> V2Scorer.koreanEvidence(text, counts) ||
                V2Scorer.chineseEvidence(text, counts) ||
                V2Scorer.japaneseEvidence(text, counts) ||
                V2Scorer.englishEvidence(text, counts)
            else -> false
        }
        if (!evidenced) {
            return V2IngestResult.Quarantined(
                V2QuarantineReason.DECLARED_MISMATCH,
                "declared $declared without $id language evidence"
            )
        }
        return V2IngestResult.Success(
            text,
            V2Provenance(charset.name(), id, 95, V2DetectMethod.DECLARED, fffd)
        )
    }

    private fun confidenceOf(margin: Double): Int = when {
        margin.isInfinite() -> 90
        margin >= 3.0 -> 95
        margin >= 2.0 -> 85
        else -> 75
    }

    /** ESC $ B/@、ESC ( B/J → JP。ESC $ ) C → KR。ESC $ ) A / ESC $ ( A → CN。 */
    fun sniffIso2022(bytes: ByteArray): V2CharsetId? {
        val limit = bytes.size.coerceAtMost(65536)
        var sawJp = false
        var sawKr = false
        var sawCn = false
        var i = 0
        while (i < limit) {
            if (bytes[i] != 0x1B.toByte()) {
                i++
                continue
            }
            if (i + 2 < limit) {
                val a = bytes[i + 1]
                val b = bytes[i + 2]
                if ((a == 0x24.toByte() && (b == 0x42.toByte() || b == 0x40.toByte())) ||
                    (a == 0x28.toByte() && (b == 0x42.toByte() || b == 0x4A.toByte()))
                ) {
                    sawJp = true
                }
            }
            if (i + 3 < limit) {
                val a = bytes[i + 1]
                val b = bytes[i + 2]
                val c = bytes[i + 3]
                if (a == 0x24.toByte() && b == 0x29.toByte() && c == 0x43.toByte()) sawKr = true
                if (a == 0x24.toByte() && (b == 0x29.toByte() || b == 0x28.toByte()) && c == 0x41.toByte()) sawCn = true
            }
            i++
        }
        return when {
            sawKr -> V2CharsetId.I2022KR
            sawCn -> V2CharsetId.I2022CN
            sawJp -> V2CharsetId.I2022JP
            else -> null
        }
    }
}

/** 均一仮説採点器。重み・gate・言語証拠は実測較正値（凍結仕様）。 */
internal object V2Scorer {

    private const val W_HANGUL = 25
    private const val W_CJK = 20
    private const val W_KANA = 100
    private const val W_SINGLE_SCRIPT = 4
    private const val W_LATIN_EXT = 4
    private const val W_COMBINING = 4
    private const val W_ASCII = 1
    private const val W_FFFD = 100
    private const val W_CONTROL = 100

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

    private fun resolve(id: V2CharsetId): Charset? {
        val names = when (id) {
            V2CharsetId.CP949 -> arrayOf("x-windows-949", "MS949", "EUC-KR")
            V2CharsetId.GB18030 -> arrayOf("GB18030", "GBK")
            V2CharsetId.BIG5 -> arrayOf("Big5")
            V2CharsetId.SJIS -> arrayOf("Windows-31J", "Shift_JIS")
            V2CharsetId.EUC_JP -> arrayOf("EUC-JP")
            V2CharsetId.W1252 -> arrayOf("windows-1252")
            V2CharsetId.W1254 -> arrayOf("windows-1254")
            V2CharsetId.W1258 -> arrayOf("windows-1258")
            V2CharsetId.W1251 -> arrayOf("windows-1251")
            V2CharsetId.W1253 -> arrayOf("windows-1253")
            V2CharsetId.W1255 -> arrayOf("windows-1255")
            V2CharsetId.W1256 -> arrayOf("windows-1256")
            V2CharsetId.TIS620 -> arrayOf("TIS-620", "windows-874")
            V2CharsetId.I2022JP -> arrayOf("ISO-2022-JP")
            V2CharsetId.I2022KR -> arrayOf("ISO-2022-KR")
            V2CharsetId.I2022CN -> arrayOf("ISO-2022-CN")
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

    fun resolveCharset(id: V2CharsetId): Charset? = when (id) {
        V2CharsetId.UTF8 -> StandardCharsets.UTF_8
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

    fun countScripts(text: String): ScriptCounts {
        val c = ScriptCounts()
        for (ch in text) {
            when {
                ch in ' '..'~' || ch == '\n' || ch == '\r' || ch == '\t' -> c.ascii++
                ch in '가'..'ퟣ' -> c.hangul++
                ch in 'ぁ'..'ヿ' -> c.hirakata++
                ch in '一'..'鿿' -> c.cjk++
                ch in ' '..'ɏ' || ch in 'Ḁ'..'ỿ' -> c.latinExt++
                ch in '̀'..'ͯ' -> c.combining++
                ch in 'Ѐ'..'ӿ' -> c.cyrillic++
                ch in '؀'..'ۿ' -> c.arabic++
                ch in '֐'..'׿' -> c.hebrew++
                ch in 'Ͱ'..'Ͽ' -> c.greek++
                ch in 'ก'..'࿿' -> c.thai++
                ch == '�' -> c.fffd++
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

    data class ScoredHypothesis(
        val id: V2CharsetId,
        val charset: Charset,
        val text: String,
        val score: Int,
        val counts: ScriptCounts
    )

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

    fun koreanEvidence(text: String, s: ScriptCounts): Boolean {
        if (s.hangul < minEvidence(text.length)) return false
        var top = 0
        for (ch in text) {
            if (ch in '가'..'ퟣ' && ch in V2LanguageModels.KO_TOP_SYLLABLES) top++
        }
        return top.toDouble() / s.hangul >= TOP_COVERAGE_MIN
    }

    fun chineseEvidence(text: String, s: ScriptCounts): Boolean {
        if (s.cjk < minEvidence(text.length)) return false
        var top = 0
        for (ch in text) {
            if (ch in '一'..'鿿' && ch in V2LanguageModels.ZH_TOP_HANZI) top++
        }
        return top.toDouble() / s.cjk >= TOP_COVERAGE_MIN
    }

    fun japaneseEvidence(text: String, s: ScriptCounts): Boolean {
        if (text.isEmpty()) return false
        if (s.hirakata.toDouble() / text.length >= KANA_RATIO_MIN) return true
        var hits = 0
        var distinct = 0
        for (w in V2LanguageModels.JA_FUNCTION_WORDS) {
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

    fun englishEvidence(text: String, s: ScriptCounts): Boolean {
        if (text.isEmpty()) return false
        val len = text.length.toDouble()
        if (s.ascii / len < EN_ASCII_MIN) return false
        if (s.latinExt / len > EN_LATIN_EXT_MAX) return false
        return true
    }

    fun latinEvidence(s: ScriptCounts, len: Int): Boolean {
        if (len == 0) return false
        val d = len.toDouble()
        return s.ascii / d >= EN_ASCII_MIN && s.latinExt / d <= EN_LATIN_EXT_MAX
    }

    private fun singleByteIds(): List<V2CharsetId> = listOf(
        V2CharsetId.W1252, V2CharsetId.W1254, V2CharsetId.W1258,
        V2CharsetId.W1251, V2CharsetId.W1253, V2CharsetId.W1255,
        V2CharsetId.W1256, V2CharsetId.TIS620
    )

    private fun isSingleByteId(id: V2CharsetId): Boolean = id in singleByteIds()

    fun eligible(id: V2CharsetId, text: String, s: ScriptCounts): Boolean {
        val len = text.length.toDouble()
        if (len == 0.0) return false
        if (s.fffd / len > VALID_FFFD_RATIO) return false
        return when (id) {
            V2CharsetId.CP949 -> koreanEvidence(text, s)
            V2CharsetId.GB18030, V2CharsetId.BIG5 -> chineseEvidence(text, s)
            V2CharsetId.SJIS, V2CharsetId.EUC_JP -> japaneseEvidence(text, s)
            V2CharsetId.W1252, V2CharsetId.W1254, V2CharsetId.W1258 ->
                latinEvidence(s, text.length)
            V2CharsetId.W1251 -> s.cyrillic / len >= SINGLE_SCRIPT_MIN
            V2CharsetId.W1253 -> s.greek / len >= SINGLE_SCRIPT_MIN
            V2CharsetId.W1255 -> s.hebrew / len >= SINGLE_SCRIPT_MIN
            V2CharsetId.W1256 -> s.arabic / len >= SINGLE_SCRIPT_MIN
            V2CharsetId.TIS620 -> s.thai / len >= SINGLE_SCRIPT_MIN
            else -> false
        }
    }

    fun eligibleTolerantUtf8(text: String, s: ScriptCounts): Boolean {
        if (text.isEmpty()) return false
        val budget = maxOf(TOLERANT_FFFD_ABS.toDouble(), text.length * TOLERANT_FFFD_RATIO).toInt()
        if (s.fffd > budget) return false
        return koreanEvidence(text, s) || chineseEvidence(text, s) ||
            japaneseEvidence(text, s) || englishEvidence(text, s)
    }

    fun rankEligible(bytes: ByteArray): List<ScoredHypothesis> {
        val out = mutableListOf<ScoredHypothesis>()
        val utf8 = tolerantDecode(StandardCharsets.UTF_8, bytes) ?: return out
        val utf8Counts = countScripts(utf8)
        if (eligibleTolerantUtf8(utf8, utf8Counts)) {
            out.add(
                ScoredHypothesis(
                    V2CharsetId.UTF8, StandardCharsets.UTF_8, utf8,
                    blockScore(utf8Counts), utf8Counts
                )
            )
        }
        for (id in listOf(
            V2CharsetId.CP949, V2CharsetId.GB18030, V2CharsetId.BIG5,
            V2CharsetId.SJIS, V2CharsetId.EUC_JP,
            V2CharsetId.W1252, V2CharsetId.W1254, V2CharsetId.W1258,
            V2CharsetId.W1251, V2CharsetId.W1253, V2CharsetId.W1255,
            V2CharsetId.W1256, V2CharsetId.TIS620
        )) {
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

    fun resolveSingleByteFamily(
        bytes: ByteArray,
        candidates: List<ScoredHypothesis>
    ): ScoredHypothesis? {
        var first: ScoredHypothesis? = null
        var firstAvg = 0.0
        var secondAvg = 0.0
        for (cand in candidates) {
            val table = V2LanguageModels.SINGLE_BYTE_TABLES[cand.id] ?: continue
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
        data class Reject(val reason: V2QuarantineReason, val evidence: String) : Decision
    }

    fun decide(bytes: ByteArray): Decision {
        val ranked = rankEligible(bytes)
        if (ranked.isEmpty()) {
            return Decision.Reject(V2QuarantineReason.UNDETECTABLE, "no eligible hypothesis")
        }
        if (ranked.size == 1) {
            return Decision.Adopt(ranked[0], Double.POSITIVE_INFINITY)
        }
        if (ranked.all { isSingleByteId(it.id) }) {
            val winner = resolveSingleByteFamily(bytes, ranked)
                ?: return Decision.Reject(
                    V2QuarantineReason.AMBIGUOUS,
                    "single-byte family margin < $MARGIN: " + ranked.take(2).joinToString { it.id.name }
                )
            return Decision.Adopt(winner, MARGIN)
        }
        val best = ranked[0]
        val runner = ranked[1]
        if (best.score <= 0) {
            return Decision.Reject(V2QuarantineReason.UNDETECTABLE, "non-positive score")
        }
        val margin = best.score.toDouble() / runner.score.coerceAtLeast(1).toDouble()
        if (margin < MARGIN) {
            return Decision.Reject(
                V2QuarantineReason.AMBIGUOUS,
                "margin $margin < $MARGIN: ${best.id} vs ${runner.id}"
            )
        }
        return Decision.Adopt(best, margin)
    }
}

/**
 * 塊検証器。分割済みチャンクが文字化けでないことを3層で検査する。
 * 検出器とは独立した防御網。理由を返す（null＝正常）。
 */
object V2ChunkVerifier {

    const val FFFD_RATIO_THRESHOLD = 0.01
    const val FFFD_MIN_COUNT = 5
    const val MIN_SCRIPT_LEN = 100
    const val EXPECTED_SCRIPT_MIN_RATIO = 0.01
    const val EXPECTED_CJK_MIN_RATIO = 0.05
    const val ASCII_DOMINANT_RATIO = 0.80
    const val LATIN_EXT_DENSE_RATIO = 0.30
    const val LATIN_MIN_ASCII_RATIO = 0.50

    fun verify(text: String, expected: V2CharsetId): String? {
        if (text.isEmpty()) return null
        val fffd = text.count { it == '�' }
        if (fffd >= FFFD_MIN_COUNT && fffd.toDouble() / text.length > FFFD_RATIO_THRESHOLD) {
            return "FFFD=$fffd/${text.length}"
        }
        if (expected == V2CharsetId.UTF8) return null
        val s = V2Scorer.countScripts(text)
        val impossible = when (expected) {
            V2CharsetId.W1252, V2CharsetId.W1254, V2CharsetId.W1258,
            V2CharsetId.W1251, V2CharsetId.W1253, V2CharsetId.W1255,
            V2CharsetId.W1256, V2CharsetId.TIS620 ->
                s.hangul + s.hirakata + s.cjk > 0
            V2CharsetId.SJIS, V2CharsetId.EUC_JP, V2CharsetId.I2022JP ->
                s.hangul > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            V2CharsetId.CP949, V2CharsetId.I2022KR ->
                s.hirakata > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            V2CharsetId.GB18030, V2CharsetId.BIG5, V2CharsetId.I2022CN ->
                s.hirakata > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            else -> false
        }
        if (impossible) return "impossible-script for $expected"
        if (text.length < MIN_SCRIPT_LEN) return null
        val len = text.length.toDouble()
        if (s.ascii / len < ASCII_DOMINANT_RATIO) {
            val missing = when (expected) {
                V2CharsetId.CP949, V2CharsetId.I2022KR ->
                    s.hangul / len < EXPECTED_SCRIPT_MIN_RATIO &&
                        s.cjk / len < EXPECTED_CJK_MIN_RATIO
                V2CharsetId.SJIS, V2CharsetId.EUC_JP, V2CharsetId.I2022JP ->
                    s.hirakata / len < EXPECTED_SCRIPT_MIN_RATIO &&
                        s.cjk / len < EXPECTED_CJK_MIN_RATIO
                V2CharsetId.GB18030, V2CharsetId.BIG5, V2CharsetId.I2022CN ->
                    s.cjk / len < EXPECTED_CJK_MIN_RATIO &&
                        s.hangul / len < EXPECTED_SCRIPT_MIN_RATIO
                else -> false
            }
            if (missing) return "missing-expected-script for $expected"
        }
        if ((expected == V2CharsetId.W1252 || expected == V2CharsetId.W1254 ||
                expected == V2CharsetId.W1258) && s.combining == 0 &&
            s.latinExt / len > LATIN_EXT_DENSE_RATIO &&
            s.ascii / len < LATIN_MIN_ASCII_RATIO
        ) {
            return "latin-gibberish for $expected"
        }
        return null
    }
}
