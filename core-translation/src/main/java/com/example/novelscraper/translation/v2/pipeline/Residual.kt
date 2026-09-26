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
 *   Blocks containing a simplified run (>=5 Han) never inherit and are always residue;
 *   isolated simplified chars without a 5-run inherit as noise.
 * - Latin-only segments are ignored, except EN-source accumulation
 *   (>= minLatinResidueChars) which is flagged as residue.
 * - ZH/KO/EN sources are checked; JA skips.
 * - ZH simplified trigger requires a consecutive Han run (>= minSimplifiedRunChars,
 *   default 5) containing a pure simplified char: isolated single-kanji noise
 *   in Japanese sentences is ignored, sentence-level leaks are kept.
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
    val minLatinResidueChars: Int = 60,
    /** Simplified trigger requires this length of consecutive Han containing pure simplified. */
    val minSimplifiedRunChars: Int = 5
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
        // 技術的根拠1行：単字では日中共通漢字を切れないため、計数と同時に簡体字入り漢字連続を見て散発ノイズと文漏れを分離する。
        var run = 0
        var runHasSimplified = false
        var hasSimplifiedRun = false
        for (ch in seg) {
            val code = ch.code
            when {
                isKana(code) -> {
                    k++
                    run = 0
                    runHasSimplified = false
                }
                isHangul(code) -> {
                    h++
                    run = 0
                    runHasSimplified = false
                }
                isHan(code) -> {
                    c++
                    val pure = LanguageModels.PURE_SIMPLIFIED_CHARS.contains(ch)
                    if (pure) s++
                    run++
                    if (pure) runHasSimplified = true
                    if (run >= options.minSimplifiedRunChars && runHasSimplified) hasSimplifiedRun = true
                }
                isLatin(code) -> {
                    l++
                    run = 0
                    runHasSimplified = false
                }
                else -> {
                    run = 0
                    runHasSimplified = false
                }
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
        // 1. 簡体字入り漢字5連続がある場合: かなが混ざっていても残留として検知。
        // 技術的根拠1行：簡体字数は漢字数に含まれるため二重計上せず、しきい値の意味を保つ。
        if (hasSimplifiedRun) {
            residue += c
            if (residueKind.isEmpty()) residueKind = "han"
            return
        }
        // 2. ハングル混入も検知（散発簡体字があっても見逃さない）。
        if (h > 0) {
            residue += h + c
            if (residueKind.isEmpty()) residueKind = "hangul"
            return
        }
        // 3. 散発簡体字のみ・かな文は日本語として合格（単漢字ノイズの無視）。
        if (s > 0 || k > 0) return

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
