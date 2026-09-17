package com.example.novelscraper

import com.example.novelscraper.ui.RecentNavigationDedup
import org.junit.Assert.*
import org.junit.Test

class NavigationDedupTest {

    @Test
    fun testFirstRequest_Allowed() {
        assertTrue(RecentNavigationDedup().shouldRequest("https://a.test/n", 0L))
    }

    @Test
    fun testRepeatWithinWindow_Suppressed() {
        val dedup = RecentNavigationDedup()
        assertTrue(dedup.shouldRequest("https://a.test/novel", 0L))
        assertTrue(dedup.shouldRequest("https://a.test/mid", 100L))
        // リダイレクト鎖の逆流（旧hopの遅延finished）による再要求は抑止
        assertFalse(dedup.shouldRequest("https://a.test/novel", 200L))
    }

    @Test
    fun testExpired_AllowsAgain() {
        val dedup = RecentNavigationDedup(windowMs = 1000L)
        assertTrue(dedup.shouldRequest("https://a.test/n", 0L))
        assertTrue(dedup.shouldRequest("https://a.test/n", 1001L))
    }

    @Test
    fun testDistinctUrls_Allowed() {
        val dedup = RecentNavigationDedup()
        assertTrue(dedup.shouldRequest("https://a.test/1", 0L))
        assertTrue(dedup.shouldRequest("https://a.test/2", 100L))
        assertTrue(dedup.shouldRequest("https://a.test/3", 200L))
    }
}
