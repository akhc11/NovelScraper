package com.example.novelscraper.translation.v2.domain

/** コスト上限到達の合図（送信済み成果を破棄して停止する）。判定は文字列一致で行う。 */
const val COST_CAP_NOTE = "cost-cap"

/**
 * run毎のコスト上限。上限超過の追加消費を拒否する。スレッドセーフ。
 * 上限なし（両方null）は常に許可する。
 */
class CostMeter(
    val maxTokens: Long? = null,
    val maxCost: Double? = null
) {
    @Volatile
    private var usedTokens: Long = 0L

    @Volatile
    private var usedCost: Double = 0.0

    @Synchronized
    fun add(tokens: Long = 0, cost: Double = 0.0): Boolean {
        if (maxTokens != null && usedTokens + tokens > maxTokens) return false
        if (maxCost != null && usedCost + cost > maxCost) return false
        usedTokens += tokens
        usedCost += cost
        return true
    }

    @Synchronized
    fun snapshot(): Pair<Long, Double> = usedTokens to usedCost
}
