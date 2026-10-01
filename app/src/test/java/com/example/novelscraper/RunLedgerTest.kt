package com.example.novelscraper

import com.example.novelscraper.translation.v2.engine.EngineState
import com.example.novelscraper.translation.v2.ui.aggregateEngineState
import com.example.novelscraper.translation.v2.ui.buildRunViews
import com.example.novelscraper.translation.v2.ui.nextFreeSlot
import com.example.novelscraper.translation.v2.ui.pruneToIds
import org.junit.Assert.*
import org.junit.Test

/**
 * 同時実行台帳の純粋部検証。
 * 技術的根拠1行：判定・集計は純粋関数に寄せたため、JVMで回帰を縛る。
 */
class RunLedgerTest {

    @Test
    fun testNextFreeSlot_PicksFirstFree() {
        assertEquals(0, nextFreeSlot(emptySet(), 2))
        assertEquals(1, nextFreeSlot(setOf(0), 2))
        assertEquals(0, nextFreeSlot(setOf(1), 2))
    }

    @Test
    fun testPruneToIds_DropsFinished() {
        val states = mapOf("a" to EngineState(), "b" to EngineState())
        assertEquals(setOf("b"), pruneToIds(states, setOf("b")).keys)
    }

    @Test
    fun testBuildRunViews_ActiveFirstNewestFirst() {
        val states = mapOf(
            "run-1" to EngineState(statusText = "old"),
            "run-2" to EngineState(statusText = "new"),
            "run-3" to EngineState(statusText = "ghost")
        )
        val meta = mapOf("run-1" to ("A" to 0), "run-2" to ("B" to 1))
        val views = buildRunViews(states, meta, setOf("run-1"))
        // meta欠け(run-3)は落とす。実行中優先、同順は新しい順。
        assertEquals(listOf("run-1", "run-2"), views.map { it.runId })
        assertTrue(views[0].active)
        assertFalse(views[1].active)
        assertEquals("B", views[1].label)
        assertEquals(1, views[1].slot)
    }

    @Test
    fun testAggregateEngineState_PrefersRunning() {
        val running = com.example.novelscraper.translation.v2.ui.RunView(
            "run-2", "B", 1, true, EngineState(isRunning = true, statusText = "run")
        )
        val done = com.example.novelscraper.translation.v2.ui.RunView(
            "run-1", "A", 0, false, EngineState(isRunning = false, statusText = "done")
        )
        val agg = aggregateEngineState(listOf(done, running))
        assertTrue(agg.isRunning)
        assertEquals("run", agg.statusText)
    }

    @Test
    fun testAggregateEngineState_EmptyIsIdle() {
        val agg = aggregateEngineState(emptyList())
        assertFalse(agg.isRunning)
        assertEquals("idle", agg.statusText)
    }
}
