package com.example.novelscraper

import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SettingsPreset
import com.example.novelscraper.translation.v2.settings.applyPresetSnapshot
import com.example.novelscraper.translation.v2.settings.newPresetId
import com.example.novelscraper.translation.v2.settings.snapshotForPreset
import org.junit.Assert.*
import org.junit.Test

/**
 * 全設定プリセットの純粋部検証。
 * 技術的根拠1行：鍵除外・鍵維持はBYOKの要のため、保存・適用の両方向をここで縛る。
 */
class V2SettingsPresetTest {

    private fun zhSettings() = V2Settings(
        geminiKeys = listOf("secret-1"),
        openRouterKey = "or-secret",
        profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-flash", maxOutputChars = 30000)),
        promptSelection = com.example.novelscraper.translation.v2.settings.V2PromptSelection(autoOrderZh = listOf(1, 1))
    )

    @Test
    fun testSnapshotForPreset_StripsKeysKeepsAll() {
        val snap = snapshotForPreset(zhSettings())
        // 鍵だけ抜く
        assertTrue(snap.geminiKeys.isEmpty())
        assertTrue(snap.openRouterKey.isEmpty())
        // 全設定（言語連動含む）は残る
        assertEquals("gemini-flash", snap.profiles.single().model)
        assertEquals(30000, snap.profiles.single().maxOutputChars)
        assertEquals(listOf(1, 1), snap.promptSelection.autoOrderZh)
    }

    @Test
    fun testApplyPresetSnapshot_RestoresCurrentKeys() {
        val current = zhSettings()
        val snapshot = snapshotForPreset(
            V2Settings(
                profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-lite", maxOutputChars = 15000))
            )
        )
        val applied = applyPresetSnapshot(current, snapshot)
        // プリセット内容が載る
        assertEquals("gemini-lite", applied.profiles.single().model)
        assertEquals(15000, applied.profiles.single().maxOutputChars)
        // 現行の鍵は維持される
        assertEquals(listOf("secret-1"), applied.geminiKeys)
        assertEquals("or-secret", applied.openRouterKey)
    }

    @Test
    fun testPreset_RoundTripSerialization() {
        val preset = V2SettingsPreset("id-1", "中文用", 123L, snapshotForPreset(zhSettings()))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val raw = json.encodeToString(V2SettingsPreset.serializer(), preset)
        val back = json.decodeFromString(V2SettingsPreset.serializer(), raw)
        assertEquals("id-1", back.id)
        assertEquals("中文用", back.label)
        assertEquals("gemini-flash", back.snapshot.profiles.single().model)
        assertTrue(back.snapshot.geminiKeys.isEmpty())
    }

    @Test
    fun testNewPresetId_Unique() {
        assertNotEquals(newPresetId(), newPresetId())
    }
}
