package com.example.novelscraper

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterNumberExtractorTest {

    @Test
    fun testExtractWithRawChapter() {
        // rawChapter が直接数字を返す場合
        val result = ChapterNumberExtractor.extract("12", "", "https://example.com/novel/123/12")
        assertEquals("0012", result)
    }

    @Test
    fun testExtractWithManualCounter() {
        // 手動カウンターが指定されている場合（@によるカウンター優先）
        val result = ChapterNumberExtractor.extract("", "", "https://example.com/novel/123/12", 5)
        assertEquals("0005", result)
    }

    @Test
    fun testExtractFromUrlPathOnly() {
        // クエリパラメータに数字が含まれる場合、それを無視してパスから抽出すること
        val result = ChapterNumberExtractor.extract("", "", "https://example.com/novel/123/45?user=999&page=2")
        assertEquals("0045", result)
    }

    @Test
    fun testExtractFromUrlPathWithFragment() {
        // ハッシュフラグメントに数字が含まれる場合、それを無視してパスから抽出すること
        val result = ChapterNumberExtractor.extract("", "", "https://example.com/novel/123/45#section-3")
        assertEquals("0045", result)
    }

    @Test
    fun testExtractEmptyFallbackToAvoid0000() {
        // 数字が含まれない場合に "0000" にならず、空文字が返されること
        val result = ChapterNumberExtractor.extract("", "", "https://example.com/novel/no-digits/")
        assertEquals("", result)
    }

    @Test
    fun testExtractForDisplay() {
        // テスト表示用の抽出テスト（クエリ付き）
        val result = ChapterNumberExtractor.extractForDisplay("", "", "https://example.com/novel/123/45?param=100")
        assertEquals("0045 (推測)", result)
        
        // 通常の抽出
        val resultNormal = ChapterNumberExtractor.extractForDisplay("12", "", "https://example.com/novel/123/45")
        assertEquals("0012", resultNormal)
        
        // 数字なし
        val resultEmpty = ChapterNumberExtractor.extractForDisplay("abc", "", "https://example.com/novel/no-digits/")
        assertEquals("", resultEmpty)
    }
}
