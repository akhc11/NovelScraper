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
     * バッチ翻訳 ([SEG:N]) 用のプロンプトを構築する
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
        enableCompletionMarker: Boolean = true
    ): String {
        val basePrompt = customPrompts?.get(promptNumber)?.ifBlank { null }
            ?: TranslationPrompts.getPromptByNumber(promptNumber)
        // OUTPUT ONLY 行を除去してバッチ指示と衝突しないようにする
        val baseTrimmed = basePrompt.lines()
            .filterNot { it.startsWith("- OUTPUT ONLY:") || it.startsWith("OUTPUT ONLY:") }
            .joinToString("\n")

        val sb = StringBuilder()
        sb.append("""
CRITICAL — READ BEFORE STARTING:
This input contains $fileCount text segment(s). Each segment begins with a marker: [SEG:1], [SEG:2] etc.
THE MARKERS ARE STRUCTURAL DELIMITERS, NOT TEXT TO TRANSLATE.
You MUST copy each marker into your output exactly as written.

$baseTrimmed

BATCH OUTPUT FORMAT (this overrides all other output instructions):
- Start your response with the first marker: [SEG:1]
- Immediately after each marker, write the translated text for that segment.
- Then the next marker, then its translation, and so on.
- Do NOT skip any marker. Do NOT add text before [SEG:1].
- EXACT required structure:
[SEG:1]
(Japanese translation of segment 1)
[SEG:2]
(Japanese translation of segment 2)
... continue for all $fileCount segment(s).
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

        // 完了マーカー (最終セグメント末尾)
        if (enableCompletionMarker) {
            sb.append("\n\n- The LAST segment's source text ends with the marker ")
            sb.append(CompletionMarkerHelper.MARKER)
            sb.append(" appended after its actual content.\n")
            sb.append("- THIS MARKER IS ALSO A STRUCTURAL DELIMITER, NOT TEXT TO TRANSLATE (same rule as [SEG:N]).\n")
            sb.append("- You MUST copy it into your output exactly as written, as the very last line of the LAST segment's translation.\n")
            sb.append("- Do NOT add this marker after any segment other than the last one.\n\n")
            sb.append("Updated exact structure (final segment only):\n...\n[SEG:$fileCount]\n(Japanese translation of segment $fileCount)\n")
            sb.append(CompletionMarkerHelper.MARKER)
        }

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
            } else if (exampleCount < 10) {
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