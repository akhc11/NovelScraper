package com.example.novelscraper.translation.v2.pipeline

/**
 * 小ファイル束ね（バッチ）の入出力。`<doc id>` 束ね→`<trans id>` 分離。
 * 完走判定は閉じタグの存在で行う（`[SRC_END]` は使わない）。
 */

/**
 * 原文中の構造タグ (`<doc>`, `<trans>`, `<documents>`, `<translations>` 等) との衝突だけを全角化する。
 * 無関係な `<` (不等号等) は温存し、翻訳品質への影響を最小化する。
 */
fun escapeBatchInput(text: String): String {
    if (!text.contains('<')) return text
    val regex = Regex(
        """<(?=/?(?:doc|trans|documents|translations)(?=[\s>/]))""",
        RegexOption.IGNORE_CASE
    )
    return regex.replace(text, "＜")
}

fun buildBatchInput(files: List<Pair<String, String>>): String {
    val sb = StringBuilder("<documents>\n")
    for ((index, file) in files.withIndex()) {
        sb.append("<doc id=\"${index + 1}\">\n")
        sb.append(escapeBatchInput(file.second.trim())).append("\n")
        sb.append("</doc>\n")
    }
    sb.append("</documents>")
    return sb.toString()
}

/**
 * タグ検出用に全角の山括弧・等号・引用符だけを半角化する（すべて1対1変換）。
 * 訳文本文の全角文字には手を加えないため、位置インデックスから原文を切り出す。
 */
fun normalizeFullWidthBatchTags(text: String): String {
    if (text.none { it == '＜' || it == '＞' || it == '＝' || it == '＂' || it == '＇' }) return text
    return text
        .replace('＜', '<')
        .replace('＞', '>')
        .replace('＝', '=')
        .replace('＂', '"')
        .replace('＇', '\'')
}

private fun normalizeDigits(str: String): String {
    val sb = StringBuilder(str.length)
    for (ch in str) {
        if (ch in '０'..'９') {
            sb.append((ch - '０' + '0'.code).toChar())
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

fun hasBatchClosedTags(text: String): Boolean {
    val normalized = normalizeFullWidthBatchTags(stripFences(text))
    return normalized.contains("</translations>", ignoreCase = true) ||
        Regex("""</\s*trans\s*>""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
}

/**
 * `<trans id="N">...</trans>` を寛容に抽出する。
 * クォート有無・全角タグ・全角数字・属性前後空白・大文字小文字に対応。
 * 外側の挨拶文やコードフェンスがあっても抽出し、正常に閉じられたIDを部分回収する。
 * 1件も正常に抽出できなかった場合（途絶など）は null を返す。
 */
fun parseBatchResponse(text: String): Map<Int, String>? {
    val raw = stripFences(text).trim()
    if (raw.isEmpty()) return null

    val normalized = normalizeFullWidthBatchTags(raw)
    val regex = Regex(
        """<trans\s+[^>]*?id\s*=\s*["']?([0-9０-９]+)["']?[^>]*>([\s\S]*?)</\s*trans\s*>""",
        RegexOption.IGNORE_CASE
    )
    val result = mutableMapOf<Int, String>()
    for (m in regex.findAll(normalized)) {
        val id = normalizeDigits(m.groupValues[1]).toIntOrNull() ?: continue
        if (id <= 0 || result.containsKey(id)) continue
        val bodyRange = m.groups[2]?.range ?: continue
        val content = raw.substring(bodyRange.first, bodyRange.last + 1).trim()
        if (content.isNotBlank()) {
            result[id] = content
        }
    }
    return result.ifEmpty { null }
}

