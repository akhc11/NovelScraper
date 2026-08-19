package com.example.novelscraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {

    @Test
    fun testExtractStandardHttpsUrl() {
        val input = "https://ncode.syosetu.com/n12345/1/"
        val result = UrlExtractor.extractUrl(input)
        assertEquals("https://ncode.syosetu.com/n12345/1/", result)
    }

    @Test
    fun testExtractStandardHttpUrl() {
        val input = "http://example.com/novel/1"
        val result = UrlExtractor.extractUrl(input)
        assertEquals("http://example.com/novel/1", result)
    }

    @Test
    fun testExtractUrlWithTitleAndNewlines() {
        // Chromeやブラウザからの共有でよくある「タイトル + 改行 + URL」パターン
        val input = "ダンジョンに出会いを求めるのは間違っているだろうか\nhttps://ncode.syosetu.com/n12345/1/"
        val result = UrlExtractor.extractUrl(input)
        assertEquals("https://ncode.syosetu.com/n12345/1/", result)
    }

    @Test
    fun testExtractUrlWithWhitespace() {
        val input = "   \n\t https://example.com/page?id=100#section \n  "
        val result = UrlExtractor.extractUrl(input)
        assertEquals("https://example.com/page?id=100#section", result)
    }

    @Test
    fun testExtractUrlFromSentence() {
        val input = "おすすめの小説はこちら: https://kakuyomu.jp/works/1234567890 です。ぜひ読んでください！"
        val result = UrlExtractor.extractUrl(input)
        assertEquals("https://kakuyomu.jp/works/1234567890", result)
    }

    @Test
    fun testExtractGenericDomainWithoutProtocol() {
        val input = "ncode.syosetu.com/n12345/1/"
        val result = UrlExtractor.extractUrl(input)
        assertEquals("https://ncode.syosetu.com/n12345/1/", result)
    }

    @Test
    fun testExtractNullOrBlank() {
        assertNull(UrlExtractor.extractUrl(null))
        assertNull(UrlExtractor.extractUrl(""))
        assertNull(UrlExtractor.extractUrl("   \n\t  "))
    }

    @Test
    fun testExtractPlainNonUrlText() {
        val input = "これはURLを含まない単なる日本語の文章です。"
        val result = UrlExtractor.extractUrl(input)
        assertNull(result)
    }

    @Test
    fun testExtractOverlyLongText() {
        val longText = "https://example.com/" + "a".repeat(5001)
        val result = UrlExtractor.extractUrl(longText)
        assertNull(result)
    }

    @Test
    fun testExtractUrlWithTrailingPunctuationAndBrackets() {
        // 日本語句読点や閉じカッコが末尾に付着しているケース
        val inputWithPeriod = "おすすめ小説: https://ncode.syosetu.com/n12345/1/。"
        assertEquals("https://ncode.syosetu.com/n12345/1/", UrlExtractor.extractUrl(inputWithPeriod))

        val inputWithParen = "(https://ncode.syosetu.com/n12345/1/)"
        assertEquals("https://ncode.syosetu.com/n12345/1/", UrlExtractor.extractUrl(inputWithParen))

        val inputWithBrackets = "【https://ncode.syosetu.com/n12345/1/】"
        assertEquals("https://ncode.syosetu.com/n12345/1/", UrlExtractor.extractUrl(inputWithBrackets))

        val inputWithExclamation = "必読です！https://ncode.syosetu.com/n12345/1/！"
        assertEquals("https://ncode.syosetu.com/n12345/1/", UrlExtractor.extractUrl(inputWithExclamation))
    }
}
