package com.example.novelscraper

import com.example.novelscraper.translation.llm.prompt.TranslationPrompts
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.buildBatchFormat
import com.example.novelscraper.translation.v2.pipeline.buildProfilePrompt
import com.example.novelscraper.translation.v2.pipeline.buildSystemPrompt
import com.example.novelscraper.translation.v2.pipeline.getV2PromptByNumber
import com.example.novelscraper.translation.v2.pipeline.lineCountOk
import com.example.novelscraper.translation.v2.pipeline.resolvePromptOrder
import org.junit.Assert.*
import org.junit.Test

class V2PromptTest {

    @Test
    fun testPromptTexts_MatchOld() {
        // Ported content must stay byte-identical to the frozen source.
        for (n in 1..7) {
            assertEquals("prompt $n", TranslationPrompts.getPromptByNumber(n), getV2PromptByNumber(n))
        }
        assertEquals(TranslationPrompts.getPromptByNumber(1), getV2PromptByNumber(99))
    }

    @Test
    fun testResolvePromptOrder_Table() {
        // Custom beats everything.
        assertEquals(listOf(4, 7), resolvePromptOrder(SourceLang.KO, listOf(4, 7), true, true))
        // Auto by language.
        assertEquals(listOf(3, 7), resolvePromptOrder(SourceLang.KO, listOf(1, 1), false, true))
        assertEquals(listOf(1, 1), resolvePromptOrder(SourceLang.ZH, listOf(2, 2), false, true))
        assertEquals(listOf(2, 7), resolvePromptOrder(SourceLang.EN, listOf(1, 1), false, true))
        // JA falls back to the profile order.
        assertEquals(listOf(5, 6), resolvePromptOrder(SourceLang.JA, listOf(5, 6), false, true))
        // Manual without auto.
        assertEquals(listOf(6, 6), resolvePromptOrder(SourceLang.KO, listOf(6, 6), false, false))
        // Empty falls back to [1, 1].
        assertEquals(listOf(1, 1), resolvePromptOrder(SourceLang.EN, emptyList(), false, false))
        assertEquals(listOf(1, 1), resolvePromptOrder(SourceLang.EN, emptyList(), true, true))
    }

    @Test
    fun testLineCountOk_Boundaries() {
        val src10 = (1..10).joinToString("\n") { "第${it}行の本文です。" }
        val dst2 = "まとめ訳文一行目。\nまとめ訳文二行目。\n"
        assertFalse(lineCountOk(src10, dst2))
        val dst4 = (1..4).joinToString("\n") { "訳文${it}行目。" }
        assertTrue(lineCountOk(src10, dst4))
        // Short sources bypass.
        assertTrue(lineCountOk("一行。\n二行。", "一行訳。"))
        // Empty output never passes the caller, but the pure rule ignores empties.
        assertTrue(lineCountOk("", ""))
    }

    @Test
    fun testBuildProfilePrompt_Replacement() {
        val base1 = getV2PromptByNumber(1)
        val base7 = getV2PromptByNumber(7)
        val basePrompts = mapOf(1 to base1, 7 to base7)

        val originalPrompt = buildSystemPrompt(
            basePrompt = base1,
            previousTranslatedTail = "前の訳文",
            previousSourceTail = "前の原文",
            batchFormat = "\n\nBATCH FORMAT..."
        )
        assertTrue(originalPrompt.startsWith(base1))
        assertTrue(originalPrompt.contains("前の訳文"))
        assertTrue(originalPrompt.contains("BATCH FORMAT..."))

        // 1番から7番へ安全に差し替え
        val profile7Prompt = buildProfilePrompt(
            originalPrompt = originalPrompt,
            basePrompts = basePrompts,
            originalPromptNum = 1,
            targetPromptNum = 7
        )
        assertTrue(profile7Prompt.startsWith(base7))
        assertFalse(profile7Prompt.startsWith(base1))
        // 付帯指示（文脈やバッチ枠）が完全に保持されていること
        assertTrue(profile7Prompt.contains("前の訳文"))
        assertTrue(profile7Prompt.contains("前の原文"))
        assertTrue(profile7Prompt.contains("BATCH FORMAT..."))

        // 同一番号なら同一インスタンス
        assertEquals(originalPrompt, buildProfilePrompt(originalPrompt, basePrompts, 1, 1))
    }

    @Test
    fun testBuildBatchFormat_TagProtection() {
        val batchFormat = buildBatchFormat(3)
        // タグ必須コンテナ宣言が含まれていること
        assertTrue(batchFormat.contains("MANDATORY STRUCTURAL CONTAINERS"))
        // タグ外限定禁止指示が含まれていること
        assertTrue(batchFormat.contains("OUTSIDE the tags are strictly forbidden"))
    }
}
