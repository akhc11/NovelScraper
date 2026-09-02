package com.example.novelscraper.translation.llm.rotation

import com.example.novelscraper.translation.llm.engine.ModelProfile
import kotlinx.coroutines.delay

/**
 * 1ワーカー専有型の Gemini ローテーションマネージャー。
 * bash スクリプト _advance_gemini_rotation と同一の動作:
 *   - 専有中のキーのまま、429発生時にモデルプロファイルを即座に巡回。
 *   - 全モデルを一周したら、キープールから「未使用の新しいキー」をアトミックに専有。
 *   - 新キーが確保できない (全キー使用済み) 場合は、isExhausted = true となりワーカーを安全に終了させる。
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
        return if (profiles.isNotEmpty()) profiles[currentProfileIndex] else ModelProfile(modelName = "gemini-3.5-flash")
    }

    fun getCurrentModel(): String = getCurrentProfile().modelName

    /**
     * 429 Quota制限検知時に即座に次のモデル/新キーへ切り替える。
     * @return 切り替え成功時は true、全キー・全モデル枯渇時は false
     */
    suspend fun advanceRotation(onLog: (String) -> Unit = {}): Boolean {
        if (isExhausted || profiles.isEmpty()) return false

        currentProfileIndex++
        if (currentProfileIndex >= profiles.size) {
            // 現在のキーで全モデルを一周した → 未使用の新キーを確保
            currentProfileIndex = 0

            if (keyPoolManager != null) {
                val newClaim = keyPoolManager.claimNewKey()
                if (newClaim != null) {
                    currentKeyIndex = newClaim.first
                    currentKey = newClaim.second
                    val active = profiles[currentProfileIndex]
                    onLog("🔁 [W#$workerId] Geminiローテーション(新キー確保): キー[${currentKeyIndex + 1}/${keyPoolManager.totalKeyCount}] / ${active.modelName} (${switchCooldownSec}秒待機)")
                    if (switchCooldownSec > 0) {
                        delay(switchCooldownSec * 1000L)
                    }
                    return true
                } else {
                    // 全キーがすでに専有済み
                    isExhausted = true
                    onLog("🛑 [W#$workerId] Geminiローテーション: 全キー使用済みのため、このワーカーは終了します")
                    return false
                }
            } else {
                onLog("🔁 [W#$workerId] 全モデルを一周 → 先頭(${profiles[0].modelName})に戻って継続")
            }
        }

        val active = profiles[currentProfileIndex]
        onLog("🔁 [W#$workerId] モデル切替 → キー[${currentKeyIndex + 1}] / ${active.modelName}")
        return true
    }
}