package com.example.novelscraper.translation.llm.rotation

import com.example.novelscraper.translation.llm.engine.ModelProfile
import kotlinx.coroutines.delay

/**
 * 1ワーカー専有型の Gemini ローテーションマネージャー。
 *
 * - 429発生時、専有中のキーのままモデルプロファイルを巡回。
 * - 全モデルを一周したら、現在のキーを 429 (RPD日次上限 or RPM一時制限) として登録し、新キーを確保。
 * - 一時制限で全キー待機中の場合は最短待機時間を自動待機して復活 (即死自滅を防止)。
 * - 当日枠が完全に枯渇した場合のみ isExhausted = true でワーカーを安全終了。
 */
class LlmRotationManager(
    val workerId: Int,
    private var currentKeyIndex: Int,
    private var currentKey: String,
    private var profiles: List<ModelProfile>,
    private val keyPoolManager: ApiKeyPoolManager?,
    val switchCooldownSec: Int = 15
) {
    private var currentProfileIndex = 0
    var isExhausted: Boolean = false
        private set

    val poolCapacity: Int
        get() = profiles.size.coerceAtLeast(1)

    fun getCurrentKey(): String = currentKey

    fun getCurrentKeyIndex(): Int = currentKeyIndex

    fun getCurrentProfile(): ModelProfile {
        return if (profiles.isNotEmpty()) profiles[currentProfileIndex.coerceIn(0, profiles.size - 1)] else ModelProfile(modelName = "gemini-3.5-flash")
    }

    /**
     * 429 Quota制限検知時に即座に次のモデル/新キーへ切り替える。
     * @param errorMessage 429 エラーメッセージ (RPD/RPM 判別用)
     * @return 切り替え成功時は true、全キー・全モデル完全枯渇時は false
     */
    suspend fun advanceRotation(
        errorMessage: String = "",
        onLog: (String) -> Unit = {}
    ): Boolean {
        if (isExhausted || profiles.isEmpty()) return false

        currentProfileIndex++
        if (currentProfileIndex < profiles.size) {
            val active = profiles[currentProfileIndex]
            onLog("🔁 [W#$workerId] モデル切替 → キー[${currentKeyIndex + 1}] / ${active.modelName}")
            return true
        }

        // 現在のキーで全モデルを一周した → 現在のキーを 429 登録し、新キーを確保
        currentProfileIndex = 0

        if (keyPoolManager != null) {
            val isDaily = keyPoolManager.reportQuotaExceeded(currentKeyIndex, errorMessage, switchCooldownSec)
            if (isDaily) {
                onLog("🛑 [W#$workerId] Gemini キー[${currentKeyIndex + 1}] は本日上限(RPD)に達しました (当日除外)")
            } else {
                onLog("⏳ [W#$workerId] Gemini キー[${currentKeyIndex + 1}] 一時レート制限(RPM)検知 → ${switchCooldownSec}秒クールダウン")
            }

            // 新しいキーの確保を試行 (一時クールダウン中は待機して自動復活)
            while (!isExhausted) {
                when (val claimResult = keyPoolManager.claimAvailableKey(null)) {
                    is KeyClaimResult.Success -> {
                        currentKeyIndex = claimResult.keyIndex
                        currentKey = claimResult.apiKey
                        val active = profiles[currentProfileIndex]
                        onLog("🔁 [W#$workerId] Geminiローテーション(新キー確保): キー[${currentKeyIndex + 1}/${keyPoolManager.totalKeyCount}] / ${active.modelName}")
                        return true
                    }
                    is KeyClaimResult.CooldownWait -> {
                        val waitSec = (claimResult.waitMillis / 1000).coerceAtLeast(1)
                        onLog("⏳ [W#$workerId] 全キー一時制限中: 最短クールダウン解除まで ${waitSec}秒 待機...")
                        delay(claimResult.waitMillis)
                    }
                    is KeyClaimResult.AllExhausted -> {
                        isExhausted = true
                        onLog("🛑 [W#$workerId] Geminiローテーション: 全キーが本日上限枯渇のため、このワーカーは終了します")
                        return false
                    }
                }
            }
            return false
        } else {
            onLog("🔁 [W#$workerId] 全モデルを一周 → 先頭(${profiles[0].modelName})に戻って継続")
            val active = profiles[currentProfileIndex]
            onLog("🔁 [W#$workerId] モデル切替 → キー[${currentKeyIndex + 1}] / ${active.modelName}")
            return true
        }
    }

    /**
     * ワーカー終了時に現在保持しているキーをプールに解放する
     */
    suspend fun release() {
        keyPoolManager?.releaseKey(currentKeyIndex)
    }
}
