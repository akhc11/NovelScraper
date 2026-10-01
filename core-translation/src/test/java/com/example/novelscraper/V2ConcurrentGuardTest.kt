package com.example.novelscraper

import com.example.novelscraper.translation.v2.engine.canStartRun
import org.junit.Assert.*
import org.junit.Test

/**
 * 同時実行ガードの検証。
 * 技術的根拠1行：上限・重複判定は純粋関数に寄せたため、UIと同一判定をここで縛る。
 */
class V2ConcurrentGuardTest {

    @Test
    fun testCanStartRun_AllowsFirst() {
        assertNull(canStartRun(0, 2, emptySet(), setOf("a")))
    }

    @Test
    fun testCanStartRun_BlocksThird() {
        val reason = canStartRun(2, 2, setOf("a", "b"), setOf("c"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("2件"))
    }

    @Test
    fun testCanStartRun_BlocksOverlap() {
        val reason = canStartRun(1, 2, setOf("a"), setOf("a", "b"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("重複"))
    }

    @Test
    fun testCanStartRun_AllowsDistinctSecond() {
        assertNull(canStartRun(1, 2, setOf("a"), setOf("b")))
    }
}
