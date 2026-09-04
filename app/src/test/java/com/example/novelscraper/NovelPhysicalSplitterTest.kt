package com.example.novelscraper

import com.example.novelscraper.translation.common.NovelPhysicalSplitter
import org.junit.Assert.*
import org.junit.Test

class NovelPhysicalSplitterTest {

    @Test
    fun testSplitLinesIntoChunks_CharacterLimit() {
        // 1行約60文字のテキストを60行作成 (計約3,600文字)
        val lines = (1..60).map { i ->
            "これは第${i}行目のテスト文章です。主人公は荒野を歩き続け、やがて巨大な城の前に辿り着きました。城の門は固く閉ざされていました。"
        }

        // 1,000文字単位で分割
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(lines.asSequence(), splitSizeChars = 1000)

        assertTrue("Should split into multiple chunks", chunks.size >= 3)
        for (chunk in chunks) {
            // 末尾以外のチャンクは1,000文字前後であること
            assertTrue("Chunk should have content", chunk.isNotEmpty())
        }

        // 全チャンクの合計行数が60行であること
        val totalLines = chunks.sumOf { it.lines().filter { l -> l.isNotEmpty() }.size }
        assertEquals(60, totalLines)
    }

    @Test
    fun testSplitLinesIntoChunks_CleansesHarmfulCharacters() {
        // NULLバイト、埋め込みBOM、ゼロ幅スペースを含む行
        val dirtyLines = listOf(
            "第1話\uFEFFの\u0000タイトルです。",
            "本文に\u200Bゼロ幅\u0000スペースが\uFEFF混入しています。"
        )

        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(dirtyLines.asSequence(), splitSizeChars = 500)
        assertEquals(1, chunks.size)
        val result = chunks.first()

        assertFalse("Should not contain NULL byte", result.contains("\u0000"))
        assertFalse("Should not contain embedded BOM", result.contains("\uFEFF"))
        assertFalse("Should not contain zero-width space", result.contains("\u200B"))
        assertTrue("Should keep original readable text", result.contains("第1話のタイトルです。"))
        assertTrue("Should keep original readable text", result.contains("本文にゼロ幅スペースが混入しています。"))
    }

    @Test
    fun testSplitLinesIntoChunks_OversizedLineFallback() {
        // 改行が一切ない2,000文字の超長行
        val longText = "あ".repeat(2000)
        val lines = listOf(longText)

        // 500文字で分割
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(lines.asSequence(), splitSizeChars = 500)

        assertEquals("Should split into 4 chunks of 500 characters", 4, chunks.size)
        val totalA = chunks.sumOf { it.trim().length }
        assertEquals(2000, totalA)
    }

    @Test
    fun testSplitLinesIntoChunks_EmptySequence() {
        val emptyLines = emptyList<String>()
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(emptyLines.asSequence(), splitSizeChars = 500)
        assertTrue("Empty sequence should produce no chunks", chunks.isEmpty())
    }

    @Test
    fun testSplitLinesIntoChunks_SingleShortLine() {
        val lines = listOf("短い1行だけのテキスト。")
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(lines.asSequence(), splitSizeChars = 500)
        assertEquals(1, chunks.size)
        assertEquals("短い1行だけのテキスト。\n", chunks.first())
    }
}
