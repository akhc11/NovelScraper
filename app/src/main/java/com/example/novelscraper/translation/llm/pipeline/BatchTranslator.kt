package com.example.novelscraper.translation.llm.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BatchTranslator {

    /** バッチ入出力の文書タグ */
    const val INPUT_ROOT = "documents"
    const val INPUT_TAG = "doc"

    private val batchJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 複数ファイルを `<documents><doc id="N">...</doc></documents>` 形式の1入力に結合する。
     * バッチ経路では `[SRC_END]` を付与しない（完走判定は出力側の閉じタグで行う）。
     * 原文中の構造タグ衝突 (`<trans ...>` 等) は全角化して誤分割を防ぐ。
     */
    fun buildBatchInput(
        files: List<Pair<String, String>> // (filename, content)
    ): String {
        val sb = StringBuilder()
        sb.append("<$INPUT_ROOT>\n")
        for ((index, file) in files.withIndex()) {
            val segNum = index + 1
            val cleansedContent = escapeStructuralTags(TextCleanser.cleanse(file.second).trim())
            sb.append("<$INPUT_TAG id=\"$segNum\">\n")
            sb.append(cleansedContent)
            sb.append("\n</$INPUT_TAG>\n")
        }
        sb.append("</$INPUT_ROOT>")
        return sb.toString()
    }

    /**
     * 原文中の構造タグ (`<doc>`, `<trans>` 等) との衝突だけを全角化する。
     * 無関係な `<` (不等号等) は温存し、翻訳品質への影響を最小化する。
     */
    fun escapeStructuralTags(text: String): String {
        if (!text.contains('<')) return text
        val regex = Regex(
            """<(?=/?(?:doc|trans|documents|translations)(?=[\s>/]))""",
            RegexOption.IGNORE_CASE
        )
        return regex.replace(text, "＜")
    }

    /**
     * モデルの応答からセグメント毎の訳文を抽出する。
     * 優先順位: JSON (`{"translations":[...]}`) → XML (`<trans id="N">`) → 旧形式 (`[SEG:N]`)。
     * 部分回収のため、抽出できた分だけを返す（欠番は含めない）。
     * 何も抽出できなかった場合のみ null を返す。
     */
    fun parseBatchResponse(response: String): Map<Int, String>? {
        // 1. JSONハイブリッド (Phase 2 / JSON Schema応答)
        val fenced = stripOuterFences(response).trim()
        if (fenced.startsWith("{")) {
            val jsonParsed = parseJsonResponse(fenced)
            if (!jsonParsed.isNullOrEmpty()) return jsonParsed
            // `{` で始まるが壊れている（途絶等）→ XML/旧形式の救済も試す
        }

        // 2. XMLペアタグ
        val xmlParsed = parseXmlResponse(response)
        if (xmlParsed.isNotEmpty()) return xmlParsed

        // 3. 旧形式フォールバック
        val legacyParsed = parseLegacySegResponse(response)
        if (legacyParsed.isNotEmpty()) return legacyParsed

        return null
    }

    /**
     * `<trans id="N">...</trans>` を寛容に抽出する。
     * クォート有無・全角数字・属性前後空白・大文字小文字に対応。
     * 全角の山括弧・等号・引用符（CJK系モデルの正規化）も受理する。
     * ID厳密一致のみ採用し、出現順の割当て直しはしない（誤訳混入防止）。
     */
    fun parseXmlResponse(response: String): Map<Int, String> {
        // 全角→半角は1文字対1文字のため、検出位置は原文と一致する。
        // 訳文の取り出しは原文から行い、本文の全角文字を改変しない。
        val normalized = normalizeFullWidthTags(response)
        val result = linkedMapOf<Int, String>()
        val regex = Regex(
            """<trans\s+[^>]*?id\s*=\s*["']?([0-9０-９]+)["']?[^>]*>([\s\S]*?)</\s*trans\s*>""",
            RegexOption.IGNORE_CASE
        )
        for (match in regex.findAll(normalized)) {
            val segNum = normalizeDigits(match.groupValues[1]).toIntOrNull() ?: continue
            if (segNum <= 0 || result.containsKey(segNum)) continue
            val bodyRange = match.groups[2]?.range ?: continue
            val content = response.substring(bodyRange.first, bodyRange.last + 1).trim()
            if (content.isNotBlank()) {
                result[segNum] = content
            }
        }
        return result
    }

    /**
     * タグ検出用に全角の山括弧・等号・引用符だけを半角化する（すべて1対1変換）。
     * 該当文字がなければ同一インスタンスを返す。
     * 和文で使う〈〉〔〕「」等には触れない。
     */
    fun normalizeFullWidthTags(text: String): String {
        if (text.none { it == '＜' || it == '＞' || it == '＝' || it == '＂' || it == '＇' }) return text
        return text
            .replace('＜', '<')
            .replace('＞', '>')
            .replace('＝', '=')
            .replace('＂', '"')
            .replace('＇', '\'')
    }

    /**
     * `{"translations":[{"id":1,"ja":"..."}]}` を抽出する。
     * ID厳密一致のみ採用し、出現順の割当て直しはしない。
     */
    fun parseJsonResponse(response: String): Map<Int, String>? {
        return try {
            val root = batchJson.parseToJsonElement(stripOuterFences(response).trim()).jsonObject
            val array = root["translations"]?.jsonArray ?: return null
            val result = linkedMapOf<Int, String>()
            for (item in array) {
                val obj = try {
                    item.jsonObject
                } catch (_: Exception) {
                    continue
                }
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                val ja = obj["ja"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                if (id > 0 && ja.isNotBlank() && !result.containsKey(id)) {
                    result[id] = ja
                }
            }
            result.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 旧形式 `[SEG:N]` の互換抽出。
     * モデルによる表記ゆれ (例: [SEG: 1], 【SEG:1】, **[SEG:1]**, SEG: 1) に柔軟に対応する。
     */
    fun parseLegacySegResponse(response: String): Map<Int, String> {
        val resultMap = mutableMapOf<Int, String>()
        val regex = Regex("""(?:\*{0,2})\[?【?SEG:?\s*(\d+)\]?】?(?:\*{0,2})""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(response).toList()

        if (matches.isEmpty()) return resultMap

        for (i in matches.indices) {
            val match = matches[i]
            val segNum = match.groupValues[1].toIntOrNull() ?: continue
            if (resultMap.containsKey(segNum)) continue
            val startIndex = match.range.last + 1
            val endIndex = if (i < matches.size - 1) matches[i + 1].range.first else response.length

            if (startIndex <= endIndex && startIndex <= response.length) {
                val segContent = response.substring(startIndex, endIndex.coerceAtMost(response.length)).trim()
                if (segContent.isNotBlank()) {
                    resultMap[segNum] = segContent
                }
            }
        }

        return resultMap
    }

    /**
     * Gemini `responseSchema` 用のバッチ翻訳スキーマを返す。
     * 形状: `{"translations":[{"id":int,"ja":string}]}`（深さ1階層に抑制）。
     */
    fun buildBatchJsonSchema(): kotlinx.serialization.json.JsonElement = buildJsonObject {
        put("type", JsonPrimitive("OBJECT"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "translations",
                    buildJsonObject {
                        put("type", JsonPrimitive("ARRAY"))
                        put(
                            "items",
                            buildJsonObject {
                                put("type", JsonPrimitive("OBJECT"))
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("id", buildJsonObject { put("type", JsonPrimitive("INTEGER")) })
                                        put("ja", buildJsonObject { put("type", JsonPrimitive("STRING")) })
                                    }
                                )
                                put("required", buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive("ja")) })
                            }
                        )
                    }
                )
            }
        )
        put("required", buildJsonArray { add(JsonPrimitive("translations")) })
    }

    /** 全角数字を半角化する */
    fun normalizeDigits(raw: String): String {
        if (raw.none { it in '０'..'９' }) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(if (ch in '０'..'９') '0' + (ch - '０') else ch)
        }
        return sb.toString()
    }

    /** 外周のコードフェンス（``` / ```json 等）だけを剥離する。本文中のフェンスは温存する */
    private fun stripOuterFences(text: String): String {
        var lines = text.trim().lines().toMutableList()
        if (lines.size >= 2 && lines.first().trim().startsWith("```")) {
            lines = lines.drop(1).toMutableList()
        }
        while (lines.size >= 2 && lines.last().trim() == "```") {
            lines = lines.dropLast(1).toMutableList()
        }
        return lines.joinToString("\n")
    }
}
