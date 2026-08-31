package com.example.novelscraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun testEmptyAndShortText() {
        val empty = ""
        val emptyChunks = TextChunker.splitIntoChunks(empty, 100)
        assertTrue(emptyChunks.isEmpty())

        val short = "Hello World\nThis is a test."
        val shortChunks = TextChunker.splitIntoChunks(short, 100)
        assertEquals(1, shortChunks.size)
        assertEquals(short, shortChunks[0])
    }

    @Test
    fun testLineBasedSplitting() {
        val line1 = "Line 1: 吾輩は猫である。名前はまだ無い。\n"
        val line2 = "Line 2: どこで生れたかとんと見当がつかぬ。\n"
        val line3 = "Line 3: 何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。\n"
        val text = line1 + line2 + line3

        val maxChunk = (line1.length + line2.length)
        val chunks = TextChunker.splitIntoChunks(text, maxChunk)

        assertEquals(2, chunks.size)
        assertEquals(line1 + line2, chunks[0])
        assertEquals(line3, chunks[1])
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun testLongLineSplitting() {
        val longSentence = "これは非常に長い文章です。途中に句点や読点が含まれています。" +
                "句点の直後で分割されることが期待されます。テストのために十分な長さにしています。" +
                "さらに文字を増やしてテストします。最後の文です。"
        
        val maxChunk = 30
        val chunks = TextChunker.splitIntoChunks(longSentence, maxChunk)

        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("Chunk size exceeds maxChunk: ${chunk.length} > $maxChunk", chunk.length <= maxChunk)
        }
        assertEquals(longSentence, chunks.joinToString(""))
    }

    @Test
    fun testPreservesExactCharactersWithNewlinesAndEmojis() {
        val text = "第1章　冒険の始まり🚀\n\n\n「行くぞ！」\n　太郎は叫んだ。\n\n" +
                "長い説明文...".repeat(50)

        val chunks = TextChunker.splitIntoChunks(text, 100)
        for (chunk in chunks) {
            assertTrue(chunk.length <= 100)
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun testSurrogatePairBoundaryHandling() {
        // サロゲートペア（2つのCharで1文字）が境界に並ぶ文字列
        val emojiString = "あいうえお𩸽かきくけこ🦄さしすせそ✨たちつてと𠮷"
        // 奇数・偶数の様々な上限サイズで分割テスト
        for (maxSize in 5..15) {
            val chunks = TextChunker.splitIntoChunks(emojiString, maxSize)
            for (chunk in chunks) {
                assertTrue("Chunk exceeds maxSize: ${chunk.length} > $maxSize", chunk.length <= maxSize)
                // 各チャンクの先頭や末尾に不正な孤立サロゲート文字が存在しないことを検証
                if (chunk.isNotEmpty()) {
                    assertTrue("Broken surrogate at start", !chunk.first().isLowSurrogate())
                    assertTrue("Broken surrogate at end", !chunk.last().isHighSurrogate())
                }
            }
            assertEquals(emojiString, chunks.joinToString(""))
        }
    }

    @Test
    fun testLargeFileSplittingExactReconstruction() {
        // 60KB以上の長文テキストの分割検証
        val paragraph = "This is a comprehensive chapter from a novel. It contains multiple sentences with dialogue and narrative.\n" +
                "\"We must proceed with caution,\" said the protagonist.\n" +
                "The winds howled across the frozen desolate landscape as the party moved forward into the unknown.\n\n"
        val largeText = paragraph.repeat(300) // 約60,000文字
        val maxChunkSize = 2000

        val chunks = TextChunker.splitIntoChunks(largeText, maxChunkSize)
        assertTrue(chunks.size >= 30)

        for (chunk in chunks) {
            assertTrue("Chunk length ${chunk.length} exceeds $maxChunkSize", chunk.length <= maxChunkSize)
        }

        // 結合した結果が完全一致すること
        val reconstructed = chunks.joinToString("")
        assertEquals(largeText.length, reconstructed.length)
        assertEquals(largeText, reconstructed)
    }
}
