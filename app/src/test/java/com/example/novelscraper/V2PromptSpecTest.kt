package com.example.novelscraper

import com.example.novelscraper.translation.v2.pipeline.GLOSSARY_SLOT
import com.example.novelscraper.translation.v2.pipeline.PromptBlock
import com.example.novelscraper.translation.v2.pipeline.PromptSpec
import com.example.novelscraper.translation.v2.pipeline.TermAnnotation
import com.example.novelscraper.translation.v2.pipeline.assemblePrompt
import com.example.novelscraper.translation.v2.pipeline.buildSpec
import com.example.novelscraper.translation.v2.pipeline.buildSystemPrompt
import com.example.novelscraper.translation.v2.pipeline.getV2PromptByNumber
import com.example.novelscraper.translation.v2.pipeline.promptSpecHash
import org.junit.Assert.*
import org.junit.Test

class V2PromptSpecTest {

    private data class Case(
        val head: String,
        val prevT: String? = null,
        val prevS: String? = null,
        val ann: TermAnnotation? = null,
        val glossary: Map<String, String>? = null,
        val batch: String? = null,
        val memo: String? = null,
        val marker: Boolean = true
    )

    @Test
    fun testWrapper_DelegatesToSpec_Representative() {
        // 技術的根拠1行：正本は Spec 側に一本化し、旧引数列 wrapper は委譲の同値で縛る（組合せ爆発を避け代表のみ）。
        val base1 = getV2PromptByNumber(1)
        val base2 = getV2PromptByNumber(2)
        val cases = listOf(
            Case(head = base1),
            Case(head = base1, prevT = "前の訳文"),
            Case(head = base1, prevS = "前の原文"),
            Case(head = base1, prevT = "前の訳文", prevS = "前の原文"),
            Case(head = base2, glossary = mapOf("이나" to "イナ")),
            Case(head = base2),
            Case(head = "ベースプロンプト", glossary = mapOf("이나" to "イナ"), marker = false),
            Case(head = "ベースプロンプト", ann = TermAnnotation("⟦", "⟧")),
            Case(head = base1, batch = "\n\nBATCH FORMAT..."),
            Case(head = "ベースプロンプト", memo = "[登場人物メモ]\n- 李雲：落ち着いた宗主の少年"),
            Case(head = base1, marker = false),
            Case(
                head = base2, prevT = "前の訳文", ann = TermAnnotation("⟦", "⟧"),
                glossary = mapOf("이나" to "イナ"), batch = "\n\nBATCH FORMAT...",
                memo = "[登場人物メモ]\n- イナ：参考", marker = true
            )
        )
        for ((i, c) in cases.withIndex()) {
            val spec = buildSpec(
                headNum = 1, headText = c.head,
                previousTranslatedTail = c.prevT, previousSourceTail = c.prevS,
                termAnnotation = c.ann, glossary = c.glossary,
                batchFormat = c.batch, profileMemo = c.memo,
                enableCompletionMarker = c.marker
            )
            assertEquals(
                "case $i wrapper identical",
                assemblePrompt(spec),
                buildSystemPrompt(
                    basePrompt = c.head,
                    previousTranslatedTail = c.prevT, previousSourceTail = c.prevS,
                    termAnnotation = c.ann, glossary = c.glossary,
                    enableCompletionMarker = c.marker, batchFormat = c.batch, profileMemo = c.memo
                )
            )
        }
    }

    @Test
    fun testAssemblePrompt_AllHeads_Minimal() {
        for (n in 1..7) {
            val head = getV2PromptByNumber(n)
            val spec = buildSpec(headNum = n, headText = head)
            val rendered = assemblePrompt(spec)
            // 技術的根拠1行：空表時は slot 行自体を消す仕様のため、slot 含み基底の一致は slot 直前までで判定する。
            val expectedStart = if (head.contains(GLOSSARY_SLOT)) head.substringBefore(GLOSSARY_SLOT) else head
            assertTrue("head $n starts with base", rendered.startsWith(expectedStart))
            assertFalse("head $n slot removed", rendered.contains(GLOSSARY_SLOT))
        }
    }

    @Test
    fun testPromptSpecHash_OrderSensitive() {
        val head = "ベースプロンプト"
        val a = PromptSpec(
            headNum = 1, headText = head,
            blocks = listOf(
                PromptBlock.Memo("m"),
                PromptBlock.Batch("b")
            )
        )
        val b = PromptSpec(
            headNum = 1, headText = head,
            blocks = listOf(
                PromptBlock.Batch("b"),
                PromptBlock.Memo("m")
            )
        )
        assertEquals(promptSpecHash(a), promptSpecHash(a))
        assertNotEquals(promptSpecHash(a), promptSpecHash(b))
    }
}
