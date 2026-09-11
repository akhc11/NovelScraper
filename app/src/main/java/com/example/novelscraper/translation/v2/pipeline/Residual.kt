package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.common.ingest.LanguageModels

/**
 * Residual source-language detection (v2).
 *
 * Design (see handoff for the adversarial review):
 * - Script-first, per-segment: split the output into sentences/lines and
 *   classify each segment by Unicode script counts (ranges: [ScriptKinds]).
 *   A segment with Hangul and no kana, or a long Han-only segment (ZH source), is residue.
 * - Short Han-only blocks without simplified chars inherit the surrounding answer: when the rest of
 *   the output holds kana, they are treated as headings/names, not residue.
 *   (Industry practice for mixed-script documents: short ambiguous blocks
 *   must not get their own label.)
 *   Blocks containing simplified chars never inherit and are always residue.
 * - Latin-only segments are ignored, except EN-source accumulation
 *   (>= minLatinResidueChars) which is flagged as residue.
 * - ZH/KO/EN sources are checked; JA skips.
 * - Thresholds are options, except the long-Han rule (inheritChars * 3, fixed),
 *   so behavior can be tuned without touching the algorithm.
 *
 * Threat model: accidental residue, not adversarial evasion. A model that
 * deliberately mixes one kana into residue defeats this check by design.
 * Han-only residue coexisting with kana elsewhere is inherited by design
 * (known blind spot, accepted for heading/name tolerance).
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
    val inheritChars: Int = 10,
    /** Latin-only blocks above this size trigger residue for EN source (prevents proper noun false-positives). */
    val minLatinResidueChars: Int = 60
)

private fun isKana(code: Int): Boolean = ScriptKinds.isKana(code)

private fun isHangul(code: Int): Boolean = ScriptKinds.isHangul(code)

private fun isHan(code: Int): Boolean = ScriptKinds.isHan(code)

private fun isLatin(code: Int): Boolean = ScriptKinds.isLatin(code)

private fun isSegmentBoundary(ch: Char): Boolean {
    if (ch == '\n') return true
    val code = ch.code
    return code == 0x3002 || code == 0xFF0E || code == 0x002E ||
        code == 0x0021 || code == 0x003F || code == 0xFF01 ||
        code == 0xFF1F || code == 0x2026 || code == 0x300D || code == 0x300F
}

/**
 * Returns a failure reason when residual source text is found, null otherwise.
 * Call with marker-stripped text (the marker itself is Latin).
 */
fun residualFailure(translatedText: String, options: ResidualOptions): String? {
    if (options.sourceLang != SourceLang.ZH &&
        options.sourceLang != SourceLang.KO &&
        options.sourceLang != SourceLang.EN
    ) return null

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
        var s = 0
        var l = 0
        for (ch in seg) {
            val code = ch.code
            when {
                isKana(code) -> k++
                isHangul(code) -> h++
                isHan(code) -> {
                    c++
                    if (LanguageModels.PURE_SIMPLIFIED_CHARS.contains(ch)) s++
                }
                isLatin(code) -> l++
            }
        }
        if (k + h + c + l < options.minSegmentChars) return

        if (options.sourceLang == SourceLang.KO) {
            // 韓国語ソース: ハングルが含まれていれば、文中に少量の「は」等のかながあっても韓国語残留
            if (h > 0) {
                residue += h + c
                if (residueKind.isEmpty()) residueKind = "hangul"
            }
            return
        }

        if (options.sourceLang == SourceLang.EN) {
            // 英語ソース:
            // セグメント内にかなまたは漢字が含まれていれば、日本語文中の英単語（固有名詞・用語）として許容
            if (k > 0 || c > 0) return
            // かな・漢字を含まない純粋な英字セグメントを英語残留疑いとして累積
            if (l >= options.minSegmentChars) {
                residue += l
                if (residueKind.isEmpty()) residueKind = "latin"
            }
            return
        }

        // 中国語ソース:
        // 1. 純粋簡体字が存在する場合: かなが文中に混ざっていても中国語残留として検知。
        // 技術的根拠1行：簡体字数は漢字数に含まれるため二重計上せず、しきい値の意味を保つ。
        if (s > 0) {
            residue += c
            if (residueKind.isEmpty()) residueKind = "han"
            return
        }

        // 2. ハングル混入も検知
        if (h > 0) {
            residue += h + c
            if (residueKind.isEmpty()) residueKind = "hangul"
            return
        }

        // 3. かなを含む文で純粋簡体字がなければ、正当な日本語文として合格
        if (k > 0) return

        // 4. 漢字のみのセグメント:
        // 周囲にかなが存在し（kanaTotal > 0）、純粋簡体字がない場合は日本の章見出しや技名等として許容。
        // 全文にかなが一切ない場合（全文中国語コピー）は残留としてカウント。
        if (c >= options.inheritChars || kanaTotal == 0) {
            if (kanaTotal == 0) {
                residue += c
                if (residueKind.isEmpty()) residueKind = "han"
            } else if (c >= options.inheritChars * 3) {
                // かなテキスト内でも30文字以上の長大漢字ブロックは残留疑い
                residue += c
                if (residueKind.isEmpty()) residueKind = "han"
            }
        }
    }
    for (ch in translatedText) {
        if (isSegmentBoundary(ch)) flush() else current.append(ch)
    }
    flush()

    val threshold = if (residueKind == "latin") {
        options.minLatinResidueChars
    } else {
        options.minResidueChars
    }
    if (residue >= threshold) {
        return "residual $residueKind ($residue chars)"
    }
    return null
}
