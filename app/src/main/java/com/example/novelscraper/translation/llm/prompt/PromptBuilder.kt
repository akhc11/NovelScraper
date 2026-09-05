package com.example.novelscraper.translation.llm.prompt

import com.example.novelscraper.translation.llm.pipeline.CompletionMarkerHelper

object PromptBuilder {

    /**
     * 単一ファイルまたはチャンク翻訳用のプロンプトを構築する
     */
    fun buildPrompt(
        promptNumber: Int,
        customPrompts: Map<Int, String>? = null,
        previousTranslatedSummary: String? = null,
        previousSourceTail: String? = null,
        sourceText: String? = null,
        dictionaryStyle: String? = null,
        dictionaryMap: Map<String, String>? = null,
        dictionaryGenders: Map<String, String>? = null,
        enableCompletionMarker: Boolean = true
    ): String {
        val basePrompt = customPrompts?.get(promptNumber)?.ifBlank { null }
            ?: TranslationPrompts.getPromptByNumber(promptNumber)
        val sb = StringBuilder(basePrompt)

        // 直前チャンクの翻訳後コンテキスト (チャンク分割翻訳用)
        if (!previousTranslatedSummary.isNullOrBlank()) {
            sb.append("\n\n=== PREVIOUS CONTEXT (maintain consistency) ===\n")
            sb.append("...").append(previousTranslatedSummary).append("\n")
            sb.append("================================================\n")
        }

        // 直前ファイルの原文末尾コンテキスト (参考情報として注入、訳出対象外)
        if (!previousSourceTail.isNullOrBlank()) {
            sb.append("\n\n=== PREVIOUS TEXT (context only — do NOT translate or repeat this) ===\n")
            sb.append("...").append(previousSourceTail).append("\n")
            sb.append("================================================================\n")
        }

        // 人名辞書セクション
        if (!sourceText.isNullOrBlank() && !dictionaryMap.isNullOrEmpty()) {
            val dictSection = buildDictionarySection(sourceText, dictionaryStyle ?: "カタカナ", dictionaryMap, dictionaryGenders)
            if (dictSection.isNotBlank()) {
                sb.append(dictSection)
            }
        }

        // 完了マーカー指示
        if (enableCompletionMarker) {
            sb.append("\n\nNOTE: The text to translate below ends with the marker ")
            sb.append(CompletionMarkerHelper.MARKER)
            sb.append(" appended after the actual source content.\n")
            sb.append("THIS MARKER IS A STRUCTURAL DELIMITER, NOT TEXT TO TRANSLATE.\n")
            sb.append("You MUST copy it into your output exactly as written, as the very last line, immediately after your translation.")
        }

        return sb.toString()
    }

    /**
     * バッチ翻訳 (XMLペアタグ / JSON) 用のプロンプトを構築する。
     * バッチ経路では `[SRC_END]` を使わない（完走判定は出力側の閉じタグで行う）。
     */
    fun buildBatchPrompt(
        promptNumber: Int,
        fileCount: Int,
        customPrompts: Map<Int, String>? = null,
        previousSourceTail: String? = null,
        sourceText: String? = null,
        dictionaryStyle: String? = null,
        dictionaryMap: Map<String, String>? = null,
        dictionaryGenders: Map<String, String>? = null,
        jsonMode: Boolean = false
    ): String {
        val basePrompt = customPrompts?.get(promptNumber)?.ifBlank { null }
            ?: TranslationPrompts.getPromptByNumber(promptNumber)
        // OUTPUT ONLY 行を除去してバッチ指示と衝突しないようにする
        val baseTrimmed = basePrompt.lines()
            .filterNot { it.startsWith("- OUTPUT ONLY:") || it.startsWith("OUTPUT ONLY:") }
            .joinToString("\n")

        val outputFormat = if (jsonMode) {
            """
            BATCH OUTPUT FORMAT (this overrides all other output instructions):
            - Return ONLY a single JSON object with this exact shape (no code fences, no preamble):
            {"translations": [{"id": 1, "ja": "(Japanese translation of document 1)"}, {"id": 2, "ja": "(Japanese translation of document 2)"}]}
            - Include all $fileCount document(s) with ids 1..$fileCount. Do NOT skip any id.
            - Do NOT add text before or after the JSON object.
            - PATTERN EXAMPLE (do NOT copy its content — translate YOUR input):
            Input: 2 documents ("Hello world." / "Good morning.")
            Output: {"translations": [{"id": 1, "ja": "こんにちは、世界。"}, {"id": 2, "ja": "おはようございます。"}]}
            """.trimIndent()
        } else {
            """
            BATCH OUTPUT FORMAT (this overrides all other output instructions):
            - Start your response with <translations> and end with </translations>.
            - For each document, output exactly:
            <trans id="N">
            (Japanese translation of document N)
            </trans>
            - Do NOT skip any id (1..$fileCount). Do NOT add text outside <translations>.
            - EXACT required structure:
            <translations>
            <trans id="1">
            (Japanese translation of document 1)
            </trans>
            ... continue for all $fileCount document(s).
            </translations>
            - PATTERN EXAMPLE (do NOT copy its content — translate YOUR input):
            Input:
            <documents>
            <doc id="1">
            Hello world.
            </doc>
            <doc id="2">
            Good morning.
            </doc>
            </documents>
            Output:
            <translations>
            <trans id="1">
            こんにちは、世界。
            </trans>
            <trans id="2">
            おはようございます。
            </trans>
            </translations>
            """.trimIndent()
        }

        val sb = StringBuilder()
        sb.append("""
CRITICAL — READ BEFORE STARTING:
This input contains $fileCount document(s) wrapped as <documents><doc id="1">...</doc>...</documents>.
THE <doc> / <documents> TAGS ARE STRUCTURAL DELIMITERS, NOT TEXT TO TRANSLATE.
You MUST translate each <doc id="N"> separately. No preamble, no explanations.

$baseTrimmed

$outputFormat
""".trimIndent())

        // 直前原文末尾
        if (!previousSourceTail.isNullOrBlank()) {
            sb.append("\n\n=== PREVIOUS TEXT (context only — do NOT translate or repeat this) ===\n")
            sb.append("...").append(previousSourceTail).append("\n")
            sb.append("================================================================\n")
        }

        // 辞書
        if (!sourceText.isNullOrBlank() && !dictionaryMap.isNullOrEmpty()) {
            val dictSection = buildDictionarySection(sourceText, dictionaryStyle ?: "カタカナ", dictionaryMap, dictionaryGenders)
            if (dictSection.isNotBlank()) {
                sb.append(dictSection)
            }
        }

        // 末尾再掲：辞書の後に形式指示が埋もれないよう、出力形式を繰り返す（同文のため指示衝突なし）
        sb.append("\n\nREMINDER — OUTPUT FORMAT (repeated so it is the last instruction you read):\n")
        sb.append(outputFormat)

        return sb.toString()
    }

    private fun buildDictionarySection(
        sourceText: String,
        style: String,
        dictMap: Map<String, String>,
        gendersMap: Map<String, String>? = null
    ): String {
        val matched = StringBuilder()
        val example = StringBuilder()
        var exampleCount = 0
        var hasMatchedGender = false

        for ((key, value) in dictMap) {
            val gender = gendersMap?.get(key)?.trim()
            val hasGender = !gender.isNullOrBlank() && gender != "不明"
            val genderSuffix = if (hasGender) " (性別: $gender)" else ""
            val line = "・$key → $value$genderSuffix\n"

            if (sourceText.contains(key)) {
                matched.append(line)
                if (hasGender) {
                    hasMatchedGender = true
                }
            } else if (exampleCount < 5) {
                example.append(line)
                exampleCount++
            }
        }

        val listLabel: String
        val listBody: String
        if (matched.isNotEmpty()) {
            listLabel = "登場人物対応表"
            listBody = matched.toString()
        } else {
            listLabel = "登場人物対応表 / 参考例 (本文に一致なし、辞書からの例)"
            listBody = example.toString()
        }

        val genderGuidance = if (hasMatchedGender) {
            "また、指定された性別（男/女）に応じた自然な日本語表現（一人称・三人称・語尾等）にしてください。"
        } else ""

        return "\n\n[人名の表記統一ルール]\n人名の表記は【$style】で統一してください。$genderGuidance\n\n[$listLabel]\n$listBody"
    }
}