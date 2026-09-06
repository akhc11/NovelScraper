package com.example.novelscraper.translation.v2.pipeline

/**
 * Residual source-language detection (v2).
 *
 * Design (see handoff for the adversarial review):
 * - Script-first, per-segment: split the output into sentences/lines and
 *   classify each segment by Unicode script counts. A segment with Hangul
 *   and no kana, or a long Han-only segment (ZH source), is residue.
 * - Short Han-only blocks inherit the surrounding answer: when the rest of
 *   the output holds kana, they are treated as headings/names, not residue.
 *   (Industry practice for mixed-script documents: short ambiguous blocks
 *   must not get their own label.)
 * - Latin-only segments are never flagged (proper nouns; parity with old).
 * - Only ZH/KO sources are checked (parity with old); EN/JA skip.
 * - Thresholds are options, not hard codes, so behavior can be tuned
 *   without touching the algorithm.
 *
 * Threat model: accidental residue, not adversarial evasion. A model that
 * deliberately mixes one kana into residue defeats this check by design.
 * Partially-mixed sentences (kana present) are a known blind spot.
 *
 * All ranges use numeric code points (this file must stay ASCII-safe).
 */
data class ResidualOptions(
    val sourceLang: SourceLang,
    /** Total residue chars that fail the output. Mirrors the old 30-char floor. */
    val minResidueChars: Int = 30,
    /** Segments with fewer script chars are ignored as noise. */
    val minSegmentChars: Int = 2,
    /** Han-only blocks below this size inherit the surrounding kana text. */
    val inheritChars: Int = 10
)

private fun isKana(code: Int): Boolean =
    code in 0x3040..0x30FF || code in 0xFF61..0xFF9F

private fun isHangul(code: Int): Boolean =
    code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF

private fun isHan(code: Int): Boolean =
    code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF

private fun isSegmentBoundary(ch: Char): Boolean {
    if (ch == '\n') return true
    val code = ch.code
    return code == 0x3002 || code == 0xFF0E || code == 0x002E ||
        code == 0x0021 || code == 0x003F || code == 0xFF01 ||
        code == 0xFF1F || code == 0x2026
}

/**
 * Returns a failure reason when residual source text is found, null otherwise.
 * Call with marker-stripped text (the marker itself is Latin).
 */
fun residualFailure(translatedText: String, options: ResidualOptions): String? {
    if (options.sourceLang != SourceLang.ZH && options.sourceLang != SourceLang.KO) return null

    var kanaTotal = 0
    for (ch in translatedText) {
        if (isKana(ch.code)) kanaTotal++
    }

    var residue = 0
    var residueKind = ""
    val current = StringBuilder()
    fun flush() {
        if (current.isEmpty()) return
        val seg = current.toString()
        current.clear()
        var k = 0
        var h = 0
        var c = 0
        for (ch in seg) {
            val code = ch.code
            when {
                isKana(code) -> k++
                isHangul(code) -> h++
                isHan(code) -> c++
            }
        }
        if (k + h + c < options.minSegmentChars) return
        if (k > 0) return
        if (h > 0) {
            residue += h + c
            if (residueKind.isEmpty()) residueKind = "hangul"
            return
        }
        // Han-only block: ambiguous (Chinese residue vs Japanese heading/name).
        if (options.sourceLang == SourceLang.ZH) {
            if (c >= options.inheritChars || kanaTotal == 0) {
                residue += c
                if (residueKind.isEmpty()) residueKind = "han"
            }
            // Else: short block inside kana text -> inherit, ignore.
        }
        // KO source: han-only passes (parity with old behavior).
    }
    for (ch in translatedText) {
        if (isSegmentBoundary(ch)) flush() else current.append(ch)
    }
    flush()

    if (residue >= options.minResidueChars) {
        return "residual $residueKind ($residue chars)"
    }
    return null
}
