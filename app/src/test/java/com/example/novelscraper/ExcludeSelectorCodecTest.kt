package com.example.novelscraper

import com.example.novelscraper.scraper.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ExcludeSelectorCodec（除外セレクタ文字列の結合仕様）の単体テスト。
 * 仕様: カンマ区切り/trim/空要素除去/完全一致重複排除/「カンマ+スペース」結合
 */
class ExcludeSelectorCodecTest {

    @Test
    fun merge_空文字から追加() {
        assertEquals(".ad", ExcludeSelectorCodec.merge("", ".ad"))
    }

    @Test
    fun merge_複数追加はカンマスペース結合() {
        var current = ""
        current = ExcludeSelectorCodec.merge(current, ".ad")
        current = ExcludeSelectorCodec.merge(current, ".banner")
        assertEquals(".ad, .banner", current)
    }

    @Test
    fun merge_重複は追加せず正規化のみ() {
        val result = ExcludeSelectorCodec.merge(".ad, .banner", ".ad")
        assertEquals(".ad, .banner", result)
    }

    @Test
    fun merge_既存要素の前後空白はtrimされる() {
        val result = ExcludeSelectorCodec.merge("  .ad , .banner  ", ".new")
        assertEquals(".ad, .banner, .new", result)
    }

    @Test
    fun merge_空要素は除去される() {
        val result = ExcludeSelectorCodec.merge(",,.ad,,,.banner,,", ".new")
        assertEquals(".ad, .banner, .new", result)
    }

    @Test
    fun remove_存在する要素を除去() {
        val result = ExcludeSelectorCodec.remove(".ad, .banner, .p-navi", ".banner")
        assertEquals(".ad, .p-navi", result)
    }

    @Test
    fun remove_存在しない場合は正規化のみ() {
        val result = ExcludeSelectorCodec.remove(".ad,.banner", ".none")
        assertEquals(".ad, .banner", result)
    }

    @Test
    fun remove_空文字からは空文字() {
        assertEquals("", ExcludeSelectorCodec.remove("", ".ad"))
    }

    @Test
    fun remove_全要素除去で空文字() {
        assertEquals("", ExcludeSelectorCodec.remove(".ad", ".ad"))
    }
}
