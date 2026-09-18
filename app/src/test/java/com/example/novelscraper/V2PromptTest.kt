package com.example.novelscraper

import com.example.novelscraper.translation.v2.pipeline.GLOSSARY_SLOT
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.TermAnnotation
import com.example.novelscraper.translation.v2.pipeline.assemblePrompt
import com.example.novelscraper.translation.v2.pipeline.buildBatchFormat
import com.example.novelscraper.translation.v2.pipeline.buildSpec
import com.example.novelscraper.translation.v2.pipeline.buildSystemPrompt
import com.example.novelscraper.translation.v2.pipeline.getV2PromptByNumber
import com.example.novelscraper.translation.v2.pipeline.lineCountOk
import com.example.novelscraper.translation.v2.pipeline.requireHeadText
import com.example.novelscraper.translation.v2.pipeline.resolvePromptOrder
import org.junit.Assert.*
import org.junit.Test

class V2PromptTest {

    @Test
    fun testPromptTexts_V2PromptsNotEmpty() {
        for (n in 1..7) {
            val prompt = getV2PromptByNumber(n)
            assertTrue("Prompt $n should not be empty", prompt.isNotEmpty())
        }
        assertEquals(getV2PromptByNumber(1), getV2PromptByNumber(99))
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
        // Upper bound: maxMultiplier = 3 (up to 30 lines for 10 source lines passes, 31 fails)
        val dst30 = (1..30).joinToString("\n") { "訳文${it}行目。" }
        assertTrue(lineCountOk(src10, dst30))
        val dst31 = (1..31).joinToString("\n") { "訳文${it}行目。" }
        assertFalse(lineCountOk(src10, dst31))
        // Short sources bypass (even if output has many lines).
        assertTrue(lineCountOk("一行。\n二行。", (1..20).joinToString("\n") { "訳文${it}行目。" }))
        // Empty output never passes the caller, but the pure rule ignores empties.
        assertTrue(lineCountOk("", ""))
    }

    @Test
    fun testPromptSpec_RetargetPreservesAttachments() {
        val base1 = getV2PromptByNumber(1)
        val base7 = getV2PromptByNumber(7)
        val basePrompts = mapOf(1 to base1, 7 to base7)

        val spec1 = buildSpec(
            headNum = 1,
            headText = requireHeadText(basePrompts, 1),
            previousTranslatedTail = "前の訳文",
            previousSourceTail = "前の原文",
            batchFormat = "\n\nBATCH FORMAT..."
        )
        val originalPrompt = assemblePrompt(spec1)
        assertTrue(originalPrompt.contains("前の訳文"))
        // 重ね注入はしない（訳文末尾がある場合は原文末尾を落とす）
        assertFalse(originalPrompt.contains("前の原文"))
        assertTrue(originalPrompt.contains("BATCH FORMAT..."))

        // 1番から7番へは Spec 複写＋再描画で差し替え（文字列手術なし）
        val spec7 = spec1.copy(headNum = 7, headText = requireHeadText(basePrompts, 7))
        val profile7Prompt = assemblePrompt(spec7)
        assertTrue(profile7Prompt.startsWith(base7))
        assertFalse(profile7Prompt.startsWith(base1))
        // 付帯指示（文脈やバッチ枠）が完全に保持されていること
        assertTrue(profile7Prompt.contains("前の訳文"))
        assertFalse(profile7Prompt.contains("前の原文"))
        assertTrue(profile7Prompt.contains("BATCH FORMAT..."))

        // 訳文末尾がない場合は原文末尾を注入する
        val srcOnly = assemblePrompt(
            buildSpec(headNum = 1, headText = base1, previousSourceTail = "前の原文")
        )
        assertTrue(srcOnly.contains("前の原文"))

        // 全番号×最小付帯の描画一致（slot 除去を考慮）
        for (n in 1..7) {
            val head = getV2PromptByNumber(n)
            val spec = buildSpec(
                headNum = n, headText = head,
                previousTranslatedTail = "前の訳文",
                batchFormat = "\n\nBATCH FORMAT..."
            )
            val rendered = assemblePrompt(spec)
            assertTrue("head $n keeps attachments", rendered.contains("前の訳文"))
            assertTrue("head $n keeps attachments", rendered.contains("BATCH FORMAT..."))
            assertFalse("head $n slot resolved", rendered.contains(GLOSSARY_SLOT))
        }

        // 同一 Spec の再描画は同一文
        assertEquals(originalPrompt, assemblePrompt(spec1))
    }

    @Test
    fun testBuildBatchFormat_TagProtection() {
        val batchFormat = buildBatchFormat(3)
        // タグ必須コンテナ宣言が含まれていること
        assertTrue(batchFormat.contains("MANDATORY STRUCTURAL CONTAINERS"))
        // タグ外限定禁止指示が含まれていること
        assertTrue(batchFormat.contains("OUTSIDE the tags are strictly forbidden"))
    }

    @Test
    fun testBuildSystemPrompt_GlossarySlot() {
        val terms = mapOf("이나" to "イナ")
        // 指定行あり：人名規則の隣に混ざり、指定行自体は残らないこと
        val mixed = buildSystemPrompt(
            basePrompt = "人名ルール\n[人物対応表挿入位置]\n出力制約",
            glossary = terms
        )
        assertFalse(mixed.contains("[人物対応表挿入位置]"))
        assertTrue(mixed.contains("[人物対応表]"))
        assertTrue(mixed.indexOf("[人物対応表]") > mixed.indexOf("人名ルール"))
        assertTrue(mixed.indexOf("[人物対応表]") < mixed.indexOf("出力制約"))
        // 指定行なし：従来通り末尾に付くこと
        val appended = buildSystemPrompt(basePrompt = "ベースプロンプト", glossary = terms, enableCompletionMarker = false)
        assertTrue(appended.contains("[人物対応表]"))
        assertTrue(appended.endsWith("- 이나 → イナ\n"))
        // 表なし：指定行は消えること
        val removed = buildSystemPrompt(basePrompt = "人名ルール\n[人物対応表挿入位置]\n出力制約")
        assertFalse(removed.contains("[人物対応表挿入位置]"))
        assertFalse(removed.contains("[人物対応表]"))
    }

    @Test
    fun testBuildSystemPrompt_AnnotationOnly() {
        val promptWithAnn = buildSystemPrompt(
            basePrompt = "ベースプロンプト",
            termAnnotation = TermAnnotation("⟦", "⟧")
        )
        // 対応表ブロックは廃止され、確定訳語の1行指示のみになること
        assertFalse(promptWithAnn.contains("[登場人物対応表]"))
        assertTrue(promptWithAnn.contains("[確定訳語]"))
        assertTrue(promptWithAnn.contains("言い換え・修正は厳禁"))
        // 勝手な人名表記固定（カタカナ等）が注入されないこと
        assertFalse(promptWithAnn.contains("[人名の表記統一ルール]"))
        assertFalse(promptWithAnn.contains("カタカナ"))
        // 注釈なし時はどちらも含まれないこと
        val plain = buildSystemPrompt(basePrompt = "ベースプロンプト")
        assertFalse(plain.contains("[確定訳語]"))
        assertFalse(plain.contains("[登場人物対応表]"))
    }

    @Test
    fun testBuildSystemPrompt_ProfileMemo() {
        // メモあり：参考ブロックが付き、訳語確定の指示にはならないこと
        val withMemo = buildSystemPrompt(
            basePrompt = "ベースプロンプト",
            profileMemo = "[登場人物メモ]\n- 李雲：落ち着いた宗主の少年"
        )
        assertTrue(withMemo.contains("[登場人物メモ]"))
        assertTrue(withMemo.contains("- 李雲：落ち着いた宗主の少年"))
        // メモなし・空：何も付かないこと（従来動作）
        assertFalse(buildSystemPrompt(basePrompt = "ベースプロンプト").contains("[登場人物メモ]"))
        assertFalse(
            buildSystemPrompt(basePrompt = "ベースプロンプト", profileMemo = "   ").contains("[登場人物メモ]")
        )
    }
}

