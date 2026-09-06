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
 * 3. 枯渇管理は (キー×モデル) ペア単位。Googleの枠はモデル別 (…PerProjectPerModel) のため、
 *    同一キーでも他モデル枠が生きていれば使い続ける (キー単位殺しによる誤停止を防止)。
 * 4. 全キーが一時クールダウン中の場合は最短待機時間を算出して安全待機 (ワーカー即死を防止)。
 */
class ApiKeyPoolManager(
    private val apiKeys: List<String>
) {
    private val mutex = Mutex()
    private val claimedIndices = mutableSetOf<Int>()
    // 日次枯渇ペア (キー番号 × 正規化モデル名)。モデル別枠のためキー単位では殺さない。
    private val dailyExhaustedPairs = mutableSetOf<Pair<Int, String>>()
    private val cooldownUntilMap = ConcurrentHashMap<Int, Long>()

    val totalKeyCount: Int
        get() = apiKeys.size

    private fun normModel(model: String): String = model.trim().lowercase(java.util.Locale.US)

    /** 指定ペアの日次枯渇有無 (Mutex内専用)。空モデル名はワイルドカード扱いで全モデルに一致する。 */
    private fun isPairDeadLocked(keyIndex: Int, model: String): Boolean {
        val m = normModel(model)
        return dailyExhaustedPairs.any { it.first == keyIndex && (it.second.isEmpty() || it.second == m) }
    }

    /** 指定キーが対象モデル群すべてで使えないか (Mutex内専用)。モデル不明時は使える扱い (他プロバイダー作業の足止め防止)。 */
    private fun isKeyDeadForAllLocked(keyIndex: Int, targets: List<String>): Boolean {
        if (targets.isEmpty()) return false
        return targets.all { isPairDeadLocked(keyIndex, it) }
    }

    private fun normTargets(models: Collection<String>): List<String> =
        models.map { normModel(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * 429 エラーメッセージから RPD (日次上限) か RPM (分次一時制限) かを判別し登録する。
     * @param model 429発生時のモデル名 ((キー×モデル)ペアで記録するため必須)
     * @return true: RPD (日次枯渇), false: RPM (一時制限)
     */
    suspend fun reportQuotaExceeded(
        keyIndex: Int,
        model: String,
        errorMessage: String,
        cooldownSec: Int
    ): Boolean = mutex.withLock {
        claimedIndices.remove(keyIndex)

        val isDaily = isDailyQuotaExceeded(errorMessage)
        if (isDaily) {
            dailyExhaustedPairs.add(keyIndex to normModel(model))
            cooldownUntilMap.remove(keyIndex)
        } else {
            val effectiveCooldown = cooldownSec.coerceAtLeast(5)
            cooldownUntilMap[keyIndex] = System.currentTimeMillis() + (effectiveCooldown * 1000L)
        }
        isDaily
    }

    /**
     * 指定 (キー×モデル) ペアが日次枯渇か。ローテーションの無駄打ち防止用。
     */
    suspend fun isPairDead(keyIndex: Int, model: String): Boolean = mutex.withLock {
        isPairDeadLocked(keyIndex, model)
    }

    /**
     * 利用可能なキーを排他専有する。
     * @param currentHoldingKeyIndex 呼び出し元ワーカーが現在手放すキーのインデックス (あれば解放)
     * @param models 使用予定モデル群。指定時は全モデル枯渇キーを候補から除外する (空＝旧来のキー単位判定)
     */
    suspend fun claimAvailableKey(
        currentHoldingKeyIndex: Int? = null,
        models: Collection<String> = emptyList()
    ): KeyClaimResult = mutex.withLock {
        if (currentHoldingKeyIndex != null) {
            claimedIndices.remove(currentHoldingKeyIndex)
        }

        if (apiKeys.isEmpty()) {
            return@withLock KeyClaimResult.AllExhausted
        }
        val targets = normTargets(models)

        val now = System.currentTimeMillis()

        // 1. クールダウン期限切れをクリーンアップ
        for (i in apiKeys.indices) {
            val until = cooldownUntilMap[i]
            if (until != null && now >= until) {
                cooldownUntilMap.remove(i)
            }
        }

        // 2. 今すぐ利用可能なキー (未専有 & 対象モデル未枯渇 & クールダウン中ではない) を探索
        for (i in apiKeys.indices) {
            if (!claimedIndices.contains(i) && !isKeyDeadForAllLocked(i, targets) && !cooldownUntilMap.containsKey(i)) {
                claimedIndices.add(i)
                return@withLock KeyClaimResult.Success(i, apiKeys[i])
            }
        }

        // 3. 今すぐ使えるキーはないが、クールダウン中のキーがある場合、最短待機時間を算出
        val candidateCooldowns = apiKeys.indices
            .filter { !isKeyDeadForAllLocked(it, targets) && !claimedIndices.contains(it) }
            .mapNotNull { cooldownUntilMap[it] }

        if (candidateCooldowns.isNotEmpty()) {
            val minUntil = candidateCooldowns.minOrNull() ?: (now + 1000L)
            val waitRemaining = (minUntil - now).coerceAtLeast(1000L)
            return@withLock KeyClaimResult.CooldownWait(waitRemaining)
        }

        // 4. 全キーが専有中または対象モデル枯渇
        if (claimedIndices.isNotEmpty()) {
            // 他のワーカーが使っている場合は少し待機して再確認可能
            return@withLock KeyClaimResult.CooldownWait(3000L)
        }

        KeyClaimResult.AllExhausted
    }

    /**
     * 指定モデル群が永続的に使えない状態か (キー未登録 or 全モデル×全キーが日次枯渇)。
     * RPM一時クールダウンのみの場合は復活するため false を返す。
     * 技術的根拠1行: 一時制限と日次枯渇を区別し、(キー×モデル)単位で復活見込みのない時だけ上位の中止判断に使う。
     */
    suspend fun isPermanentlyExhausted(models: Collection<String>): Boolean = mutex.withLock {
        if (apiKeys.isEmpty()) return@withLock true
        val targets = normTargets(models)
        if (targets.isEmpty()) {
            // モデル不明時は旧来のキー単位判定 (いずれか枯渇ペアを持つキーが全キー分あれば真)
            return@withLock apiKeys.indices.all { idx -> dailyExhaustedPairs.any { it.first == idx } }
        }
        return@withLock targets.all { m -> apiKeys.indices.all { k -> isPairDeadLocked(k, m) } }
    }

    /**
     * 単純な初期キー専有 (後方互換用)
     */
    suspend fun claimNewKey(models: Collection<String> = emptyList()): Pair<Int, String>? = mutex.withLock {
        val res = claimAvailableKeyInternal(normTargets(models))
        if (res is KeyClaimResult.Success) {
            res.keyIndex to res.apiKey
        } else null
    }

    private fun claimAvailableKeyInternal(targets: List<String>): KeyClaimResult {
        val now = System.currentTimeMillis()
        for (i in apiKeys.indices) {
            if (!claimedIndices.contains(i) && !isKeyDeadForAllLocked(i, targets)) {
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
        dailyExhaustedPairs.clear()
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
