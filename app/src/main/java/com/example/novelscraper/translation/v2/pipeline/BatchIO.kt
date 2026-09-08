package com.example.novelscraper.translation.v2.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 小ファイル束ね（バッチ）の入出力。`<doc id>` 束ね→`<trans id>` 分離。
 * 完走判定は閉じタグの存在で行う（`[SRC_END]` は使わない）。ただしJSON形式の応答は閉じタグなしで完走扱いする。
 */

/** 呼出毎コンパイルを避けるため正規表現は共有する（pure・スレッドセーフ） */
private val BATCH_TAG_ESCAPE_REGEX = Regex(
    """<(?=/?(?:doc|trans|documents|translations)(?=[\s>/]))""",
    RegexOption.IGNORE_CASE
)
private val BATCH_JSON_RESCUE_ID_FIRST = Regex(
    """["']id["']\s*:\s*([0-9０-９]+)\s*,\s*["'](?:ja|text|translation|content)["']\s*:\s*"((?:[^"\\]|\\.)*)"""",
    RegexOption.IGNORE_CASE
)
private val BATCH_JSON_RESCUE_TEXT_FIRST = Regex(
    """["'](?:ja|text|translation|content)["']\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*["']id["']\s*:\s*([0-9０-９]+)""",
    RegexOption.IGNORE_CASE
)
private val BATCH_JSON_RESCUE_KEYMAP = Regex(
    """["']([0-9０-９]+)["']\s*:\s*"((?:[^"\\]|\\.)*)"""",
    RegexOption.IGNORE_CASE
)
private val BATCH_TRANS_REGEX = Regex(
    """<trans\s+[^>]*?id\s*=\s*["']?([0-9０-９]+)["']?[^>]*>([\s\S]*?)</\s*trans\s*>""",
    RegexOption.IGNORE_CASE
)
private val BATCH_TRANS_UNCLOSED_REGEX = Regex(
    """<trans\s+[^>]*?id\s*=\s*["']?([0-9０-９]+)["']?[^>]*>([\s\S]*)$""",
    RegexOption.IGNORE_CASE
)
private val BATCH_CHAPTER_REGEX = Regex(
    """(?:第\s*([0-9０-９一二三四五六七八九十百千万]+)\s*[話章節回]|Chapter\s*([0-9]+))""",
    RegexOption.IGNORE_CASE
)
private val BATCH_WS_REGEX = Regex("""\s+""")
private val BATCH_NUM_REGEX = Regex("""[0-9０-９]{2,}""") // 偶然の一致を避けるため2桁以上の数字

/**
 * 原文中の構造タグ (`<doc>`, `<trans>`, `<documents>`, `<translations>` 等) との衝突だけを全角化する。
 * 無関係な `<` (不等号等) は温存し、翻訳品質への影響を最小化する。
 */
fun escapeBatchInput(text: String): String {
    if (!text.contains('<')) return text
    return BATCH_TAG_ESCAPE_REGEX.replace(text, "＜")
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



private val batchJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Gemini / OpenAI 構造化出力（Structured Outputs）用 JSON Schema 定義。
 */
fun buildBatchJsonSchema(): String {
    return """
    {
      "type": "OBJECT",
      "properties": {
        "translations": {
          "type": "ARRAY",
          "description": "List of translated documents corresponding to each document id",
          "items": {
            "type": "OBJECT",
            "properties": {
              "id": { "type": "INTEGER", "description": "The document id matching input doc id" },
              "text": { "type": "STRING", "description": "The translated Japanese text" }
            },
            "required": ["id", "text"]
          }
        }
      },
      "required": ["translations"]
    }
    """.trimIndent()
}

private fun unescapeJsonString(str: String): String {
    val sb = java.lang.StringBuilder(str.length)
    var i = 0
    while (i < str.length) {
        val c = str[i]
        if (c == '\\' && i + 1 < str.length) {
            when (val next = str[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    if (i + 5 < str.length) {
                        val hex = str.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(c)
                    i++
                }
                else -> {
                    sb.append(next)
                    i += 2
                }
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/**
 * 途絶やJSON構文エラー時の正規表現救済パーサー。
 * `{"id": 1, "text": "..."}` や `{"id": 1, "ja": "..."}` を完成部分から抽出する。
 */
private fun parseBatchJsonRegexFallback(raw: String): Map<Int, String>? {
    val result = linkedMapOf<Int, String>()
    // パターン1: {"id": N, "(text|ja|translation)": "CONTENT"}
    for (m in BATCH_JSON_RESCUE_ID_FIRST.findAll(raw)) {
        val id = normalizeDigits(m.groupValues[1]).toIntOrNull() ?: continue
        if (id >= 0 && !result.containsKey(id)) {
            val content = unescapeJsonString(m.groupValues[2]).trim()
            if (content.isNotBlank()) result[id] = content
        }
    }
    // パターン2: {"(text|ja|translation)": "CONTENT", "id": N}
    for (m in BATCH_JSON_RESCUE_TEXT_FIRST.findAll(raw)) {
        val id = normalizeDigits(m.groupValues[2]).toIntOrNull() ?: continue
        if (id >= 0 && !result.containsKey(id)) {
            val content = unescapeJsonString(m.groupValues[1]).trim()
            if (content.isNotBlank()) result[id] = content
        }
    }
    // パターン3: 単純キーマップ {"1": "CONTENT", "2": "CONTENT"}
    if (result.isEmpty()) {
        for (m in BATCH_JSON_RESCUE_KEYMAP.findAll(raw)) {
            val id = normalizeDigits(m.groupValues[1]).toIntOrNull() ?: continue
            if (id >= 0 && !result.containsKey(id)) {
                val content = unescapeJsonString(m.groupValues[2]).trim()
                if (content.isNotBlank()) result[id] = content
            }
        }
    }
    return result.ifEmpty { null }
}

/**
 * モデルが JSON で応答した際のパーサー。
 * `{"translations": [{"id": 1, "text": "..."}]}`、トップレベル配列 `[{"id": 1, ...}]`、
 * キーマップ `{"1": "...", "2": "..."}`、および途絶救済に対応。
 */
fun parseBatchJsonResponse(text: String): Map<Int, String>? {
    val trimmed = stripFences(text).trim()
    if (trimmed.isEmpty()) return null

    // 1. kotlinx.serialization による構文解析
    try {
        val jsonStr = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            trimmed.startsWith("[") && trimmed.endsWith("]") -> trimmed
            trimmed.contains("{") && trimmed.contains("}") -> {
                val start = trimmed.indexOf('{')
                val end = trimmed.lastIndexOf('}')
                trimmed.substring(start, end + 1)
            }
            trimmed.contains("[") && trimmed.contains("]") -> {
                val start = trimmed.indexOf('[')
                val end = trimmed.lastIndexOf(']')
                trimmed.substring(start, end + 1)
            }
            else -> ""
        }
        if (jsonStr.isNotEmpty()) {
            val element = batchJson.parseToJsonElement(jsonStr)
            val result = linkedMapOf<Int, String>()

            // ケースA: トップレベル配列 [{"id": 1, ...}]
            val array = if (element is kotlinx.serialization.json.JsonArray) {
                element
            } else if (element is kotlinx.serialization.json.JsonObject) {
                // ケースB: {"translations": [...]} または {"data": [...]}
                val innerArray = (element["translations"] ?: element["data"] ?: element["results"])?.jsonArray
                if (innerArray == null) {
                    // ケースC: {"1": "...", "2": "..."} のキーマップ形式
                    for ((k, v) in element) {
                        val id = normalizeDigits(k).toIntOrNull() ?: continue
                        val content = v.jsonPrimitive.contentOrNull?.trim() ?: continue
                        if (id >= 0 && content.isNotBlank() && !result.containsKey(id)) {
                            result[id] = content
                        }
                    }
                    if (result.isNotEmpty()) return result
                }
                innerArray
            } else null

            if (array != null) {
                for (item in array) {
                    val obj = try {
                        item.jsonObject
                    } catch (_: Exception) {
                        continue
                    }
                    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val content = obj["text"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: obj["ja"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: obj["translation"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: continue
                    if (id >= 0 && content.isNotBlank() && !result.containsKey(id)) {
                        result[id] = content
                    }
                }
                if (result.isNotEmpty()) return result
            }
        }
    } catch (_: Exception) {
        // 構文エラー時はフォールバックへ進む
    }

    // 2. 途絶・構文破壊時の正規表現救済
    return parseBatchJsonRegexFallback(trimmed)
}

/**
 * `<trans id="N">...</trans>` のXMLタグ形式パーサー。
 * クォート有無、全角タグ、全角数字、属性前後空白、大文字小文字、途絶タグ救済に対応。
 */
fun parseBatchXmlResponse(text: String): Map<Int, String>? {
    val raw = stripFences(text).trim()
    if (raw.isEmpty()) return null

    val normalized = normalizeFullWidthBatchTags(raw)
    val result = linkedMapOf<Int, String>()
    for (m in BATCH_TRANS_REGEX.findAll(normalized)) {
        val id = normalizeDigits(m.groupValues[1]).toIntOrNull() ?: continue
        if (id < 0 || result.containsKey(id)) continue
        val bodyRange = m.groups[2]?.range ?: continue
        val content = raw.substring(bodyRange.first, bodyRange.last + 1).trim()
        if (content.isNotBlank()) {
            result[id] = content
        }
    }

    // 末尾セグメントが途絶して </trans> が欠けている場合の救済（1件以上の完成タグがある場合）
    if (result.isNotEmpty()) {
        val lastMatch = BATCH_TRANS_REGEX.findAll(normalized).lastOrNull()
        if (lastMatch != null && lastMatch.range.last < normalized.length - 1) {
            val tail = normalized.substring(lastMatch.range.last + 1)
            val unclosedMatch = BATCH_TRANS_UNCLOSED_REGEX.find(tail)
            if (unclosedMatch != null) {
                val id = normalizeDigits(unclosedMatch.groupValues[1]).toIntOrNull()
                if (id != null && !result.containsKey(id)) {
                    val groupRange = unclosedMatch.groups[2]?.range
                    if (groupRange != null) {
                        val absStart = lastMatch.range.last + 1 + groupRange.first
                        val content = raw.substring(absStart).trim()
                        if (content.isNotBlank()) {
                            result[id] = content
                        }
                    }
                }
            }
        }
    }

    return result.ifEmpty { null }
}

/**
 * ハイブリッドバッチパーサー。
 * XML形式（`<trans id="N">`）および JSON形式（構造化出力）を自動判別してパースする。
 */
fun parseBatchResponse(text: String): Map<Int, String>? {
    val raw = stripFences(text).trim()
    if (raw.isEmpty()) return null

    // 1. JSON形式の優先判定（先頭が { または [、あるいは translations キーを含む場合）
    if (raw.startsWith("{") || raw.startsWith("[") || raw.contains("\"translations\"") || raw.contains("'translations'")) {
        val jsonParsed = parseBatchJsonResponse(raw)
        if (!jsonParsed.isNullOrEmpty()) return jsonParsed
    }

    // 2. XMLタグ形式のパース
    val xmlParsed = parseBatchXmlResponse(raw)
    if (!xmlParsed.isNullOrEmpty()) return xmlParsed

    // 3. XMLで取れなかった場合のJSON救済
    return parseBatchJsonResponse(raw)
}

/**
 * バッチ翻訳結果におけるファイル順序の入れ替わり（スワップ/テレコ）を検知するガード。
 * 純粋Kotlin実装（Android SDK非依存）。
 *
 * 判定基準:
 * 1. 見出しアンカー（第X話、Chapter X）の交差逆転
 * 2. 固有数字セット（アラビア数字）の交差逆転
 * スワップが検知された場合、true を返す（呼び出し元で単体フォールバックへ回す）。
 */
fun detectBatchSwap(
    items: List<Pair<String, String>>,
    translations: Map<Int, String>
): Boolean {
    if (items.size < 2 || translations.size < 2) return false

    val isZeroIndexed = translations.containsKey(0) && !translations.containsKey(items.size)
    fun getTrans(idx: Int): String? = translations[idx + 1] ?: if (isZeroIndexed) translations[idx] else null

    // 1. 見出しアンカー（第X話、Chapter X）の交差チェック
    fun extractChapter(text: String): String? {
        val head = text.lineSequence().filter { it.isNotBlank() }.take(5).joinToString("\n")
        val match = BATCH_CHAPTER_REGEX.find(head) ?: return null
        return normalizeDigits(match.value.replace(BATCH_WS_REGEX, ""))
    }

    val srcChapters = items.map { extractChapter(it.second) }
    val transChapters = items.indices.map { idx -> getTrans(idx)?.let { extractChapter(it) } }

    for (i in items.indices) {
        val srcI = srcChapters[i] ?: continue
        val transI = transChapters[i]
        for (j in i + 1 until items.size) {
            val srcJ = srcChapters[j] ?: continue
            if (srcI == srcJ) continue
            val transJ = transChapters[j]
            // iの訳文がjの章番を持ち、jの訳文がiの章番を持っている（完全なスワップ）
            if (transI == srcJ && transJ == srcI) return true
            // 片方の訳文が自話と異なり、相手の話の章番を明らかに持っている
            if (transI != null && transI != srcI && transI == srcJ) return true
            if (transJ != null && transJ != srcJ && transJ == srcI) return true
        }
    }

    // 2. 固有数字セット（アラビア数字列）の交差逆転チェック
    fun extractNumbers(text: String): Set<String> {
        return BATCH_NUM_REGEX.findAll(text).map { normalizeDigits(it.value) }.toSet()
    }

    val srcNums = items.map { extractNumbers(it.second) }
    val transNums = items.indices.map { idx -> getTrans(idx)?.let { extractNumbers(it) } ?: emptySet() }

    for (i in items.indices) {
        val srcNumI = srcNums[i]
        val transNumI = transNums[i]
        if (srcNumI.isEmpty() || transNumI.isEmpty()) continue

        for (j in i + 1 until items.size) {
            val srcNumJ = srcNums[j]
            val transNumJ = transNums[j]
            if (srcNumJ.isEmpty() || transNumJ.isEmpty()) continue

            val uniqueToI = srcNumI - srcNumJ
            val uniqueToJ = srcNumJ - srcNumI
            if (uniqueToI.size >= 2 && uniqueToJ.size >= 2) {
                val overlapIWithOwn = transNumI.intersect(uniqueToI).size
                val overlapIWithOther = transNumI.intersect(uniqueToJ).size
                val overlapJWithOwn = transNumJ.intersect(uniqueToJ).size
                val overlapJWithOther = transNumJ.intersect(uniqueToI).size

                // iの訳文がjの数字をより多く含み、jの訳文がiの数字をより多く含んでいる（スワップ）
                if (overlapIWithOther > overlapIWithOwn && overlapJWithOther > overlapJWithOwn) {
                    return true
                }
            }
        }
    }

    return false
}

