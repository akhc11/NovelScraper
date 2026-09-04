package com.example.novelscraper.translation.llm.rotation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

sealed class KeyClaimResult {
    data class Success(val keyIndex: Int, val apiKey: String) : KeyClaimResult()
    data class CooldownWait(val waitMillis: Long) : KeyClaimResult()
    object AllExhausted : KeyClaimResult()
}

/**
 * 複数ワーカー間での Gemini API キー排他専有 ＆ クールダウン復帰マネージャー。
 *
 * 1. 1ワーカー1キー専有を維持 (同一キーの重複衝突を防止)。
 * 2. 429 Quota Exceeded 時に、エラー内容から「RPD (日次上限枯渇)」と「RPM (分次一時制限)」を自動判別。
 *    - RPD: 当日枠の完全枯渇と判定し、当日中は再利用キューから完全除外。
 *    - RPM: 一時制限と判定し、指定秒数 (cooldownSec) の経過後に自動復活・再利用。
 * 3. 全キーが一時クールダウン中の場合は最短待機時間を算出して安全待機 (ワーカー即死を防止)。
 */
class ApiKeyPoolManager(
    private val apiKeys: List<String>
) {
    private val mutex = Mutex()
    private val claimedIndices = mutableSetOf<Int>()
    private val dailyExhaustedIndices = mutableSetOf<Int>()
    private val cooldownUntilMap = ConcurrentHashMap<Int, Long>()

    val totalKeyCount: Int
        get() = apiKeys.size

    val availableKeyCount: Int
        get() = apiKeys.size - claimedIndices.size - dailyExhaustedIndices.size

    /**
     * 429 エラーメッセージから RPD (日次上限) か RPM (分次一時制限) かを判別し登録する。
     * @return true: RPD (日次枯渇), false: RPM (一時制限)
     */
    suspend fun reportQuotaExceeded(
        keyIndex: Int,
        errorMessage: String,
        cooldownSec: Int
    ): Boolean = mutex.withLock {
        claimedIndices.remove(keyIndex)

        val isDaily = isDailyQuotaExceeded(errorMessage)
        if (isDaily) {
            dailyExhaustedIndices.add(keyIndex)
            cooldownUntilMap.remove(keyIndex)
        } else {
            val effectiveCooldown = cooldownSec.coerceAtLeast(5)
            cooldownUntilMap[keyIndex] = System.currentTimeMillis() + (effectiveCooldown * 1000L)
        }
        isDaily
    }

    /**
     * 利用可能なキーを排他専有する。
     * @param currentHoldingKeyIndex 呼び出し元ワーカーが現在手放すキーのインデックス (あれば解放)
     */
    suspend fun claimAvailableKey(currentHoldingKeyIndex: Int? = null): KeyClaimResult = mutex.withLock {
        if (currentHoldingKeyIndex != null) {
            claimedIndices.remove(currentHoldingKeyIndex)
        }

        if (apiKeys.isEmpty() || dailyExhaustedIndices.size >= apiKeys.size) {
            return@withLock KeyClaimResult.AllExhausted
        }

        val now = System.currentTimeMillis()

        // 1. クールダウン期限切れをクリーンアップ
        for (i in apiKeys.indices) {
            val until = cooldownUntilMap[i]
            if (until != null && now >= until) {
                cooldownUntilMap.remove(i)
            }
        }

        // 2. 今すぐ利用可能なキー (未専有 & 日次未枯渇 & クールダウン中ではない) を探索
        for (i in apiKeys.indices) {
            if (!claimedIndices.contains(i) && !dailyExhaustedIndices.contains(i) && !cooldownUntilMap.containsKey(i)) {
                claimedIndices.add(i)
                return@withLock KeyClaimResult.Success(i, apiKeys[i])
            }
        }

        // 3. 今すぐ使えるキーはないが、クールダウン中のキーがある場合、最短待機時間を算出
        val candidateCooldowns = apiKeys.indices
            .filter { !dailyExhaustedIndices.contains(it) && !claimedIndices.contains(it) }
            .mapNotNull { cooldownUntilMap[it] }

        if (candidateCooldowns.isNotEmpty()) {
            val minUntil = candidateCooldowns.minOrNull() ?: (now + 1000L)
            val waitRemaining = (minUntil - now).coerceAtLeast(1000L)
            return@withLock KeyClaimResult.CooldownWait(waitRemaining)
        }

        // 4. 全キーが専有中または日次枯渇
        if (claimedIndices.isNotEmpty()) {
            // 他のワーカーが使っている場合は少し待機して再確認可能
            return@withLock KeyClaimResult.CooldownWait(3000L)
        }

        KeyClaimResult.AllExhausted
    }

    /**
     * 単純な初期キー専有 (後方互換用)
     */
    suspend fun claimNewKey(): Pair<Int, String>? = mutex.withLock {
        val res = claimAvailableKeyInternal()
        if (res is KeyClaimResult.Success) {
            res.keyIndex to res.apiKey
        } else null
    }

    private fun claimAvailableKeyInternal(): KeyClaimResult {
        val now = System.currentTimeMillis()
        for (i in apiKeys.indices) {
            if (!claimedIndices.contains(i) && !dailyExhaustedIndices.contains(i)) {
                val until = cooldownUntilMap[i]
                if (until == null || now >= until) {
                    cooldownUntilMap.remove(i)
                    claimedIndices.add(i)
                    return KeyClaimResult.Success(i, apiKeys[i])
                }
            }
        }
        return KeyClaimResult.AllExhausted
    }

    /**
     * ワーカー終了時のキー解放
     */
    suspend fun releaseKey(keyIndex: Int) = mutex.withLock {
        claimedIndices.remove(keyIndex)
    }

    /**
     * プール状態のリセット (セッション終了時)
     */
    suspend fun reset() = mutex.withLock {
        claimedIndices.clear()
        dailyExhaustedIndices.clear()
        cooldownUntilMap.clear()
    }

    companion object {
        /**
         * 429 エラー本文から RPD (日次上限枯渇) かどうかを判定する
         */
        fun isDailyQuotaExceeded(errorMessage: String): Boolean {
            val lower = errorMessage.lowercase()
            return lower.contains("requestsperday") ||
                    lower.contains("per day") ||
                    lower.contains("perday") ||
                    lower.contains("daily limit") ||
                    lower.contains("daily quota") ||
                    lower.contains("quota exceeded for quota metric 'generatecontent requests per day'")
        }
    }
}
