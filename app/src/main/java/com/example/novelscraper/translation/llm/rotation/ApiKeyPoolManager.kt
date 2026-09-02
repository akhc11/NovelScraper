package com.example.novelscraper.translation.llm.rotation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 複数ワーカー間での Gemini API キー排他専有マネージャー。
 * bash スクリプト _gemini_claim_new_key と同一の排他仕様:
 *   - 1つのワーカーが1本のキーを専有する (同一キーの重複使用は不可)。
 *   - 使用し終えたワーカーは、キープール内の「まだ誰も使っていないキー」を早い者勝ちで専有する。
 *   - 全キーが専有済みになった場合、新キーは取得できず null を返す。
 */
class ApiKeyPoolManager(
    private val apiKeys: List<String>
) {
    private val mutex = Mutex()
    private val claimedIndices = mutableSetOf<Int>()

    val totalKeyCount: Int
        get() = apiKeys.size

    val availableKeyCount: Int
        get() = apiKeys.size - claimedIndices.size

    /**
     * 未使用のキーを1本アトミックに排他専有する。
     * @return Pair(キー番号[0..N-1], キー文字列) または 空きが無い場合は null
     */
    suspend fun claimNewKey(): Pair<Int, String>? = mutex.withLock {
        for (i in apiKeys.indices) {
            if (!claimedIndices.contains(i)) {
                claimedIndices.add(i)
                return@withLock (i to apiKeys[i])
            }
        }
        null
    }

    /**
     * プール状態のリセット (翻訳セッション終了時)
     */
    suspend fun reset() = mutex.withLock {
        claimedIndices.clear()
    }
}