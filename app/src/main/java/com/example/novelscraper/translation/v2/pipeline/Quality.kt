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
 * - 括弧（半角[], 全角［］, 丸括弧(), 全角丸括弧（））および括弧なし単体
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

/** ```剥離→前口上除去。後口上は呼び元で扱う */
fun stripFences(text: String): String {
    var t = text.trim()
    if (t.startsWith("```")) {
        val firstNl = t.indexOf('\n')
        t = if (firstNl == -1) "" else t.substring(firstNl + 1)
    }
    if (t.endsWith("```")) {
        t = t.removeSuffix("```")
    }
    return t.trim()
}

fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/**
 * サイズ比検証（原文に対する訳文のバイト比率%）。範囲は呼出側指定。
 * 下限未満＝省略疑い、上限超過＝水増し疑い。
 */
fun sizeRatioOk(sourceText: String, translatedText: String, minPct: Int, maxPct: Int): Boolean {
    val src = utf8Bytes(sourceText.trim())
    val dst = utf8Bytes(translatedText.trim())
    if (src == 0) return dst == 0
    val ratio = dst * 100.0 / src
    return ratio >= minPct && ratio <= maxPct
}

private fun isKana(ch: Char): Boolean =
    ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF'

private fun isKanji(ch: Char): Boolean =
    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'

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
 * Line-loss check (ported rule): translated non-blank lines below
 * source lines / divisor means broken paragraphs. Short sources bypass.
 * Improvement vs old: thresholds are parameters, not object constants,
 * so callers/tests tune without touching the algorithm.
 */
fun lineCountOk(
    sourceText: String,
    translatedText: String,
    minLines: Int = 5,
    divisor: Int = 3
): Boolean {
    val srcLines = sourceText.lines().filter { it.isNotBlank() }
    val outLines = translatedText.lines().filter { it.isNotBlank() }
    if (srcLines.size < minLines || outLines.isEmpty()) return true
    return outLines.size * divisor >= srcLines.size
}
