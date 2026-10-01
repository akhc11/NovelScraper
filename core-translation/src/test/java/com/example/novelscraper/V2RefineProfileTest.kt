package com.example.novelscraper

import com.example.novelscraper.translation.v2.settings.V2RefineSettings
import com.example.novelscraper.translation.v2.settings.buildRefineProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * 推敲専用プロファイル解決の検証。
 * 技術的根拠1行：継承／専用の分岐は純粋関数に寄せたため、巡回器・UI・検証の三者が同じ判定を使うことをここで縛る。
 */
class V2RefineProfileTest {

    @Test
    fun testBuildRefineProfile_BlankModelIsNull() {
        // model空＝継承のため、他項目があってもnull（従来動作）。
        assertNull(buildRefineProfile(V2RefineSettings()))
        assertNull(
            buildRefineProfile(
                V2RefineSettings(enabled = true, providerId = "openrouter", thinkingLevel = "high")
            )
        )
    }

    @Test
    fun testBuildRefineProfile_ModelOnlyDefaultsGemini() {
        // provider空＋model有り＝gemini（辞書設定と同一約束）。
        val profile = buildRefineProfile(V2RefineSettings(enabled = true, model = "gemini-2.5-pro"))!!
        assertEquals("refine", profile.id)
        assertEquals("gemini", profile.providerId)
        assertEquals("gemini-2.5-pro", profile.model)
    }

    @Test
    fun testBuildRefineProfile_CarriesThinkingOverrides() {
        // 思考系4項目は専用プロファイルに焼き込む（翻訳群への上書き表は使わない）。
        val profile = buildRefineProfile(
            V2RefineSettings(
                enabled = true,
                providerId = "openrouter",
                model = "x/y",
                thinkingLevel = "high",
                thinkingBudget = 8000,
                reasoningEffort = "medium",
                reasoningEnabled = true
            )
        )!!
        assertEquals("openrouter", profile.providerId)
        assertEquals("x/y", profile.model)
        assertEquals("high", profile.thinkingLevel)
        assertEquals(8000, profile.thinkingBudget)
        assertEquals("medium", profile.reasoningEffort)
        assertEquals(true, profile.reasoningEnabled)
    }
}
