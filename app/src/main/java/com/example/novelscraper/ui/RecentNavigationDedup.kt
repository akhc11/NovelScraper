package com.example.novelscraper.ui

/**
 * 直近に要求した正規化URLの再要求抑止。pure・JVMテスト可。
 * 技術的根拠1行：リダイレクト鎖の各hopが状態を前進させる度に旧hopへ再loadUrlすると鎖が再発火するため、要求済みは窓内では再要求しない。
 */
class RecentNavigationDedup(
    private val windowMs: Long = 10_000L,
    private val maxEntries: Int = 20
) {
    private val recent: ArrayDeque<Pair<String, Long>> = ArrayDeque()

    /** 窓内に同一正規化URLがあればfalse（抑止）。なければ記録してtrue。 */
    fun shouldRequest(normalizedUrl: String, nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first().second > windowMs) {
            recent.removeFirst()
        }
        if (recent.any { it.first == normalizedUrl }) return false
        recent.addLast(normalizedUrl to nowMs)
        while (recent.size > maxEntries) recent.removeFirst()
        return true
    }
}
