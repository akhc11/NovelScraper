package com.example.novelscraper.translation.v2.pipeline

/** 完走マーカー。訳文末尾の構造区切りであり翻訳対象外 */
const val COMPLETION_MARKER = "[SRC_END]"

fun appendMarker(content: String, enabled: Boolean): String {
    if (!enabled) return content
    if (content.trimEnd().endsWith(COMPLETION_MARKER)) return content
    return content.trimEnd() + "\n" + COMPLETION_MARKER
}

/** 完走マーカーの探索ウィンドウサイズ（末尾からの文字数） */
const val MARKER_SEARCH_WINDOW_CHARS = 300

/**
 * 完走マーカー判定用正規表現。
 * - 大文字小文字不問（(?i)）
 * - 括弧（半角[], 全角［］, 丸括弧(), 全角丸括弧（））および括弧なし単体（SRC END等の素の語も拾う）
 * - 区切り文字（アンダースコア _, ハイフン -, 空白, 連結）
 * - 前後のMarkdown装飾（太字 **, コード枠 `, 見出し # 等）
 */
private val MARKER_REGEX = Regex(
    """(?i)(?:[*`#\s]*[\[［(（]\s*SRC[_\-\s]?END\s*[\]］)）][*`#\s]*|(?:\b|[*`#\s])SRC[_\-\s]END(?:\b|[*`#\s]))"""
)

/**
 * 完走検証＋剥離（アイデアA：段階的寛容フォールバック）。
 * 末尾300文字ウィンドウ内からマーカー（装飾・表記揺れ・全角括弧対応）を探索し、
 * マーカー以降（後口上含む）を安全に切り落として本文を救出する。
 * マーカーが存在しない場合は生成途絶とみなして null。
 */
fun checkAndStripMarker(text: String, enabled: Boolean): String? {
    if (!enabled) return text
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    // 末尾300文字の探索ウィンドウ（短文なら全文）
    val searchStart = (trimmed.length - MARKER_SEARCH_WINDOW_CHARS).coerceAtLeast(0)
    val window = trimmed.substring(searchStart)

    // ウィンドウ内で最後に出現するマーカーを特定
    val match = MARKER_REGEX.findAll(window).lastOrNull() ?: return null
    val absStart = searchStart + match.range.first
    return trimmed.substring(0, absStart).trimEnd()
}

/** ```剥離のみ（前口上の除去はしない）。後口上は呼び元で扱う */
fun stripFences(text: String): String {
    var t = text.trim()
    if (t.startsWith("```")) {
        // 技術的根拠1行：言語名なしの異常な fence でも本文を捨てず枠記号だけ剥がす。
        val firstNl = t.indexOf('\n')
        t = if (firstNl == -1) t.substring(3) else t.substring(firstNl + 1)
    }
    if (t.endsWith("```")) {
        t = t.removeSuffix("```")
    }
    return t.trim()
}

/**
 * UTF-8バイト数をヒープ割り当て（toByteArray）なしで計算する。
 * 技術的根拠1行: 大量テキストや走査ループでのByteArray生成によるヒープ浪費とGC Jitterを完全に防止する。
 * 不正な単独サロゲートはJVMの符号化（? 置換＝1バイト）に合わせる。
 */
fun utf8Bytes(text: String): Int {
    var bytes = 0
    var i = 0
    val len = text.length
    while (i < len) {
        val ch = text[i].code
        when {
            ch <= 0x7F -> bytes += 1
            ch <= 0x7FF -> bytes += 2
            ch in 0xD800..0xDBFF -> {
                if (i + 1 < len && text[i + 1].code in 0xDC00..0xDFFF) {
                    bytes += 4
                    i++
                } else {
                    // 不正な単独上位サロゲートはJVMの符号化（? 置換＝1バイト）に合わせる。
                    bytes += 1
                }
            }
            ch in 0xDC00..0xDFFF -> bytes += 1
            else -> bytes += 3
        }
        i++
    }
    return bytes
}

/**
 * サイズ比検証（原文に対する訳文のバイト比率%）。範囲は呼出側指定。
 * 下限未満＝省略疑い、上限超過＝水増し・解説混入・繰り返し暴走疑い。
 *
 * 【小説翻訳における比率特性】:
 * 1行・短文の翻訳と異なり、小説翻訳（まとまった段落・チャンク単位）では文長が平均化されるため、
 * 極端な増大（300%や320%等）は正常な翻訳ではあり得ない。
 * そのような膨張はLLMのハルシネーション（幻覚・不要な追記・同一語句のループ）と判定して弾くのが安全である。
 * なお上限値自体は言語別の設定値（V2SizeRatios。英語既定220）に従う。
 */
fun sizeRatioOk(sourceText: String, translatedText: String, minPct: Int, maxPct: Int): Boolean {
    val src = utf8Bytes(sourceText.trim())
    val dst = utf8Bytes(translatedText.trim())
    if (src == 0) return dst == 0
    val ratio = dst * 100.0 / src
    return ratio >= minPct && ratio <= maxPct
}

private fun isKana(ch: Char): Boolean = ScriptKinds.isKana(ch.code)

private fun isKanji(ch: Char): Boolean = ScriptKinds.isHan(ch.code)

/** かな率＝かな／（かな＋漢字）。日本語訳文らしさの下限判定に使う */
fun kanaRate(text: String): Double {
    var kana = 0
    var kanji = 0
    for (ch in text) {
        if (isKana(ch)) kana++
        else if (isKanji(ch)) kanji++
    }
    val denom = kana + kanji
    if (denom == 0) return 0.0
    return kana.toDouble() / denom
}

fun meetsKanaFloor(text: String, minRate: Double = 0.2): Boolean = kanaRate(text) >= minRate

/**
 * 不合格理由の特定（ログ用）。合格時は null を返す。
 * 判定順序は verifyTranslation と同一にすること（順序がずれると表示と実判定が食い違う）。
 */
fun verifyRejectReason(sourceText: String, translatedText: String, options: VerifyOptions): String? {
    val stripped = stripFences(translatedText)
    val withoutMarker = checkAndStripMarker(stripped, options.markerEnabled) ?: return "marker-missing"
    val cleaned = withoutMarker.trim()
    if (cleaned.isBlank()) return "blank"
    if (options.residual != null) {
        val residual = residualFailure(cleaned, options.residual)
        if (residual != null) return residual
    }
    if (!lineCountOk(sourceText, cleaned)) return "line-count"
    if (!sizeRatioOk(sourceText, cleaned, options.sizeMinPct, options.sizeMaxPct)) return "size-ratio"
    if (!meetsKanaFloor(cleaned, options.kanaFloor)) return "kana-floor"
    return null
}

/**
 * Line-loss and explosion check:
 * - translated non-blank lines below source lines / divisor means broken paragraphs (line-loss).
 * - translated non-blank lines exceeding source lines * maxMultiplier means hallucination or newline spam (line-explosion).
 * - Short sources (< minLines) bypass.
 */
fun lineCountOk(
    sourceText: String,
    translatedText: String,
    minLines: Int = 5,
    divisor: Int = 3,
    maxMultiplier: Int = 3
): Boolean {
    val srcLines = sourceText.lines().filter { it.isNotBlank() }
    val outLines = translatedText.lines().filter { it.isNotBlank() }
    if (srcLines.size < minLines) return true
    if (outLines.isEmpty()) return false
    val minOk = outLines.size * divisor >= srcLines.size
    val maxOk = outLines.size <= srcLines.size * maxMultiplier
    return minOk && maxOk
}
