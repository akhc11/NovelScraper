package com.example.novelscraper

import org.junit.Assert.*
import org.junit.Test

/**
 * 中核 internal の white-box 検証（:app からは不可視のためここに置く）。
 * 技術的根拠1行：公開APIを広げずに内部述語を直接縛るため、同一モジュール内に置く。
 */
class V2DictInternalTest {

    @Test
    fun testDictAnnotatablePredicate() {
        assertTrue(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("李维", "レヴィ"))
        assertTrue(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("田隶", "田隷"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("文", "文"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("小灰", "小灰"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("离", "離"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("尘", "塵"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("安", "安"))
    }
}
