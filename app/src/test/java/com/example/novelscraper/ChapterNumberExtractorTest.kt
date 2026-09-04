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
        val result = ChapterNumberExtractor.extract("", "@1", "https://example.com/novel/123/12", 5)
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

    @Test
    fun testFullWidthAndWhitespaceSanitization() {
        // 全角 ＠１ や空白を含む手動指定
        val spec1 = ChapterNumberExtractor.parseManualSpec(" ＠１ ")
        assertEquals(1, spec1?.startNumber)
        assertEquals(4, spec1?.padLength)

        val spec2 = ChapterNumberExtractor.parseManualSpec("@ 25 ")
        assertEquals(25, spec2?.startNumber)
        assertEquals(4, spec2?.padLength)

        val resultDisplay = ChapterNumberExtractor.extractForDisplay("", " ＠１ ", "https://example.com/")
        assertEquals("0001 (連番開始: 次回 0002)", resultDisplay)
    }

    @Test
    fun testVariablePadLengths() {
        // @01 -> 2桁パディング
        val spec2Digit = ChapterNumberExtractor.parseManualSpec("@01")
        assertEquals(1, spec2Digit?.startNumber)
        assertEquals(2, spec2Digit?.padLength)
        assertEquals("05", ChapterNumberExtractor.extract("", "@01", "https://example.com/", 5))

        // @001 -> 3桁パディング
        val spec3Digit = ChapterNumberExtractor.parseManualSpec("@001")
        assertEquals(1, spec3Digit?.startNumber)
        assertEquals(3, spec3Digit?.padLength)
        assertEquals("005", ChapterNumberExtractor.extract("", "@001", "https://example.com/", 5))

        // @1# -> パディングなし
        val specNoPad = ChapterNumberExtractor.parseManualSpec("@1#")
        assertEquals(1, specNoPad?.startNumber)
        assertEquals(0, specNoPad?.padLength)
        assertEquals("5", ChapterNumberExtractor.extract("", "@1#", "https://example.com/", 5))
    }

    @Test
    fun testHybridSelectorAndManualCounter() {
        // ハイブリッド指定 (セレクタ || @1)
        val spec = ChapterNumberExtractor.parseManualSpec(".ep-num || @1")
        assertEquals(1, spec?.startNumber)
        assertEquals(4, spec?.padLength)

        // セレクタから取得できた場合はセレクタ値を採用
        val displayWithDom = ChapterNumberExtractor.extractForDisplay("第42話", ".ep-num || @1", "https://example.com/")
        assertEquals("0042 (セレクタ抽出 / 連番待機: 0001)", displayWithDom)

        // セレクタから取得できなかった場合は連番開始表示
        val displayFallback = ChapterNumberExtractor.extractForDisplay("", ".ep-num || @1", "https://example.com/")
        assertEquals("0001 (連番開始: 次回 0002)", displayFallback)
    }
}
