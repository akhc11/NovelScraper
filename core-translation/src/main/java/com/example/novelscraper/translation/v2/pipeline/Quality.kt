package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.FailureNotes

/**
 * 完走マーカー。原文末尾に付けて送り、訳文末尾への複写で完走を確認する。
 *
 * 【完成時の動作】
 * - 送る：原文の最後に "[SRC_END]" を付ける（例：「勇者は剣を抜いた。[SRC_END]」）
 * - 返る：訳文の最後に同じ栞があれば完成。栞を剥がして本文だけ保存する
 * - 長い挨拶付きでも可：栞の後の文章（後口上）は切り落として本文を救出する
 * - 栞なし：途切れ疑いとして null を返す（呼出側は予備の指示で再送する）
 */
const val COMPLETION_MARKER = "[SRC_END]"

fun appendMarker(content: String, enabled: Boolean): String {
    if (!enabled) return content
    if (content.trimEnd().endsWith(COMPLETION_MARKER)) return content
    return content.trimEnd() + "\n" + COMPLETION_MARKER
}

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
 * 完走検証＋剥離。
 * 全文から栞（装飾・表記揺れ・全角括弧対応）の最後の出現を探す。
 *
 * 【完成時の動作】
 * - 栞あり → 栞より後ろ（後口上）を切り落とし、本文だけ返して完成
 * - 栞なし → null を返して未完（呼出側は再送し、全滅時のみ確定失敗にする）
 */
fun checkAndStripMarker(text: String, enabled: Boolean): String? {
    if (!enabled) return text
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    // 全文から最後に出現するマーカーを特定
    val match = MARKER_REGEX.findAll(trimmed).lastOrNull() ?: return null
    return trimmed.substring(0, match.range.first).trimEnd()
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
 * 完了証明。合格か否かと、不合格の理由（[FailureNotes] の値）を一体で持つ。
 * 技術的根拠1行：合否と理由の二重実装は必ず乖離するため、検証の単一正本とする。
 */
data class CompletionReceipt(
    val complete: Boolean,
    /** 空＝合格。不合格時は理由 */
    val note: String = "",
    /** 合格時の確定訳文 */
    val cleaned: String = ""
)

/**
 * 必須語の欠落許容数。1件までは合格にする（8/9通過）。
 * 技術的根拠1行：単発言及の落とし・表記揺れの1件で章ごと捨てるより、ほぼ合った訳の採用を優先する。
 */
const val DICT_ALLOWED_MISSING = 1

/**
 * 唯一の検証実装。順序固定：栞→空白→確定訳→残留→行数→量比→かな率。
 * 技術的根拠1行：順序がずれると表示と実判定が食い違うため、順序もここに封印する。
 */
fun assessCompletion(sourceText: String, translatedText: String, options: VerifyOptions): CompletionReceipt {
    // 技術的根拠1行：注釈残骸は本文ではないため、栞・量・かな率の判定前に確定訳へ畳む。
    val fenced = stripFences(translatedText)
    val check = options.dictCheck
    val stripped = check?.strip?.let { stripTermAnnotations(fenced, it.terms, it.annotation) } ?: fenced
    val withoutMarker = checkAndStripMarker(stripped, options.markerEnabled)
        ?: return CompletionReceipt(complete = false, note = FailureNotes.MARKER_MISSING)
    val cleaned = withoutMarker.trim()
    if (cleaned.isBlank()) return CompletionReceipt(false, FailureNotes.BLANK)
    // 技術的根拠1行：辞書不遵守は内容の正誤ではなく約束違反のため、品質判定より先に件数付きで落とす（単一正本に寄せる）。
    // 必須語の選び方は言語方針の責務（中国語＝全件、韓国語＝長い名のみ）。
    check?.let {
        if (it.required.isNotEmpty()) {
            val missing = it.required.entries
                .filter { (key, value) ->
                    value.isNotBlank() && !cleaned.contains(value) &&
                        it.alternates[key].orEmpty().none { alt -> alt.isNotBlank() && cleaned.contains(alt) }
                }
                .map { it.key }
            if (missing.size > DICT_ALLOWED_MISSING) {
                val survived = it.required.size - missing.size
                return CompletionReceipt(
                    false,
                    "${FailureNotes.DICT_MISMATCH}: ${missing.take(3).joinToString(",")} (残存$survived/${it.required.size}件)"
                )
            }
        }
    }
    if (options.residual != null) {
        val residual = residualFailure(cleaned, options.residual)
        if (residual != null) return CompletionReceipt(false, residual)
    }
    if (!lineCountOk(sourceText, cleaned)) return CompletionReceipt(false, FailureNotes.LINE_COUNT)
    if (!sizeRatioOk(sourceText, cleaned, options.sizeMinPct, options.sizeMaxPct)) {
        return CompletionReceipt(false, FailureNotes.SIZE_RATIO)
    }
    if (!meetsKanaFloor(cleaned, options.kanaFloor)) {
        return CompletionReceipt(false, FailureNotes.KANA_FLOOR)
    }
    return CompletionReceipt(complete = true, cleaned = cleaned)
}

/**
 * 不合格理由の特定（ログ用）。合格時は null を返す。
 */
fun verifyRejectReason(sourceText: String, translatedText: String, options: VerifyOptions): String? {
    val receipt = assessCompletion(sourceText, translatedText, options)
    return if (receipt.complete) null else receipt.note
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
