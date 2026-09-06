package com.example.novelscraper.translation.v2.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 汎用クォータプールの確保結果 */
sealed interface AcquireResult {
    data class Ready(val credentialIndex: Int, val credential: String) : AcquireResult
    data class Wait(val waitMillis: Long) : AcquireResult
    data object Exhausted : AcquireResult
}

/**
 * (資格情報×スコープ) 汎用クォータプール。1ワーカー1資格情報の専有を維持する。
 * スコープの切り方は呼出側（記述子の quotaScopeOf 由来）が決め、本器は知らない。
 * 日次枯渇は (資格情報×スコープ) 単位で除外し、一時冷却は資格情報単位で自動復活させる。
 */
class QuotaPool(private val credentials: List<String>) {
    private val mutex = Mutex()
    private val claimed = mutableSetOf<Int>()
    private val dailyDead = mutableSetOf<Pair<Int, String>>()
    private val cooldownUntil = mutableMapOf<Int, Long>()

    suspend fun acquire(scopes: Collection<String>): AcquireResult = mutex.withLock {
        if (credentials.isEmpty()) return@withLock AcquireResult.Exhausted
        val targets = normalizeScopes(scopes)
        val now = System.currentTimeMillis()

        for (i in credentials.indices) {
            val until = cooldownUntil[i]
            if (until != null && now >= until) cooldownUntil.remove(i)
        }

        for (i in credentials.indices) {
            if (i !in claimed && !isDeadForAllLocked(i, targets) && !cooldownUntil.containsKey(i)) {
                claimed.add(i)
                return@withLock AcquireResult.Ready(i, credentials[i])
            }
        }

        val waits = credentials.indices
            .filter { !isDeadForAllLocked(it, targets) && it !in claimed }
            .mapNotNull { cooldownUntil[it] }
        if (waits.isNotEmpty()) {
            val minUntil = waits.minOrNull() ?: (now + 1000L)
            return@withLock AcquireResult.Wait((minUntil - now).coerceAtLeast(1000L))
        }

        if (claimed.isNotEmpty()) return@withLock AcquireResult.Wait(3000L)
        return@withLock AcquireResult.Exhausted
    }

    suspend fun claimNew(scopes: Collection<String> = emptyList()): Pair<Int, String>? = mutex.withLock {
        val now = System.currentTimeMillis()
        val targets = normalizeScopes(scopes)
        for (i in credentials.indices) {
            if (i !in claimed && !isDeadForAllLocked(i, targets)) {
                val until = cooldownUntil[i]
                if (until == null || now >= until) {
                    cooldownUntil.remove(i)
                    claimed.add(i)
                    return@withLock i to credentials[i]
                }
            }
        }
        null
    }

    /** 429報告。日次なら除外、一次なら冷却する */
    suspend fun reportQuota(credentialIndex: Int, scope: String, daily: Boolean, cooldownSec: Int) =
        mutex.withLock {
            claimed.remove(credentialIndex)
            if (daily) {
                dailyDead.add(credentialIndex to normalizeScope(scope))
                cooldownUntil.remove(credentialIndex)
            } else {
                val effective = cooldownSec.coerceAtLeast(5)
                cooldownUntil[credentialIndex] = System.currentTimeMillis() + (effective * 1000L)
            }
        }

    suspend fun isScopeDead(credentialIndex: Int, scope: String): Boolean = mutex.withLock {
        isDeadLocked(credentialIndex, scope)
    }

    /** 指定スコープ群が永続的に使えない状態か（全スコープ×全資格情報が日次枯渇） */
    suspend fun isExhausted(scopes: Collection<String>): Boolean = mutex.withLock {
        if (credentials.isEmpty()) return@withLock true
        val targets = normalizeScopes(scopes)
        if (targets.isEmpty()) return@withLock false
        return@withLock targets.all { s -> credentials.indices.all { k -> isDeadLocked(k, s) } }
    }

    suspend fun release(credentialIndex: Int) = mutex.withLock {
        claimed.remove(credentialIndex)
    }

    suspend fun reset() = mutex.withLock {
        claimed.clear()
        dailyDead.clear()
        cooldownUntil.clear()
    }

    private fun normalizeScope(scope: String): String = scope.trim().lowercase()

    private fun normalizeScopes(scopes: Collection<String>): List<String> =
        scopes.map { normalizeScope(it) }.filter { it.isNotEmpty() }.distinct()

    private fun isDeadLocked(credentialIndex: Int, scope: String): Boolean {
        val s = normalizeScope(scope)
        return dailyDead.any { it.first == credentialIndex && (it.second.isEmpty() || it.second == s) }
    }

    private fun isDeadForAllLocked(credentialIndex: Int, targets: List<String>): Boolean {
        if (targets.isEmpty()) return false
        return targets.all { isDeadLocked(credentialIndex, it) }
    }
}
