package com.example.novelscraper

import com.example.novelscraper.translation.v2.settings.V2CostCaps
import com.example.novelscraper.translation.v2.settings.V2DictSettings
import com.example.novelscraper.translation.v2.settings.V2Limits
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PrevContext
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SplitSettings
import com.example.novelscraper.translation.v2.ui.coercedV2Settings
import com.example.novelscraper.translation.v2.ui.validateV2Settings
import org.junit.Assert.*
import org.junit.Test

class V2SettingsValidationTest {

    private fun base() = V2Settings(
        geminiKeys = listOf("k1"),
        openRouterKey = "or",
        profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
        dict = V2DictSettings(enabled = false),
        limits = V2Limits(),
        cost = V2CostCaps()
    )

    @Test
    fun testValid_Passes() {
        assertTrue(validateV2Settings(base()).none { it.blocksSave })
    }

    @Test
    fun testEmptyProfiles_Blocks() {
        val issues = validateV2Settings(base().copy(profiles = emptyList()))
        assertTrue(issues.any { it.blocksSave })
    }

    @Test
    fun testMissingKey_Blocks() {
        val noKey = validateV2Settings(base().copy(geminiKeys = emptyList()))
        assertTrue(noKey.any { it.blocksSave && it.message.contains("Gemini") })
        val orMissing = validateV2Settings(
            base().copy(
                openRouterKey = "",
                profiles = listOf(V2ModelProfile(providerId = "openrouter", model = "x/y"))
            )
        )
        assertTrue(orMissing.any { it.blocksSave && it.message.contains("OpenRouter") })
    }

    @Test
    fun testDictEnabledWithoutModel_Blocks() {
        val issues = validateV2Settings(base().copy(dict = V2DictSettings(enabled = true, model = "")))
        assertTrue(issues.any { it.blocksSave && it.message.contains("辞書") })
    }

    @Test
    fun testUnsupportedParam_WarnsOnly() {
        // Gemma系は思考非対応のため警告のみ（保存は通す）
        val settings = base().copy(
            profiles = listOf(
                V2ModelProfile(providerId = "gemini", model = "gemma-4-31b-it", thinkingLevel = "high")
            )
        )
        val issues = validateV2Settings(settings)
        assertFalse(issues.any { it.blocksSave })
        assertTrue(issues.any { it.message.contains("思考") || it.message.contains("thinking") })
    }

    @Test
    fun testOutOfRange_WarnsAndCoerces() {
        // OpenRouter既定は温度0〜2のため99は丸め警告、Geminiは温度未対応のため別警告
        val settings = base().copy(
            profiles = listOf(
                V2ModelProfile(providerId = "openrouter", model = "x/y", temperature = 99.0)
            ),
            limits = V2Limits(parallelWorkers = 99)
        )
        val issues = validateV2Settings(settings)
        assertTrue(issues.any { it.blocksSave }) // limits側はブロック
        assertTrue(issues.any { !it.blocksSave && it.message.contains("丸め") })
        val coerced = coercedV2Settings(settings)
        assertEquals(6, coerced.limits.parallelWorkers)
    }

    @Test
    fun testOpenRouterParams_WarnsOnly() {
        val settings = base().copy(
            openRouterKey = "or",
            profiles = listOf(
                V2ModelProfile(
                    providerId = "openrouter",
                    model = "x/y",
                    reasoningEffort = "ultra",
                    providerOrder = listOf("b", "", "b"),
                    providerAllowFallbacks = true
                )
            )
        )
        val issues = validateV2Settings(settings)
        assertFalse(issues.any { it.blocksSave })
        assertTrue(issues.any { it.message.contains("reasoningEffort") })
        assertTrue(issues.any { it.message.contains("providerOrder") })

        val both = base().copy(
            openRouterKey = "or",
            profiles = listOf(
                V2ModelProfile(
                    providerId = "openrouter",
                    model = "x/y",
                    reasoningEffort = "high",
                    reasoningEnabled = true
                )
            )
        )
        assertTrue(validateV2Settings(both).any { it.message.contains("reasoningEnabled") })

        val alone = base().copy(
            openRouterKey = "or",
            profiles = listOf(
                V2ModelProfile(
                    providerId = "openrouter",
                    model = "x/y",
                    providerOrder = emptyList(),
                    providerAllowFallbacks = true
                )
            )
        )
        assertTrue(validateV2Settings(alone).any { it.message.contains("allow_fallbacks") })
    }

    @Test
    fun testNonFinite_WarnsOnly() {
        val settings = base().copy(
            profiles = listOf(
                V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", temperature = Double.NaN)
            )
        )
        val issues = validateV2Settings(settings)
        assertFalse(issues.any { it.blocksSave })
        assertTrue(issues.any { it.message.contains("有限数") })
    }

    @Test
    fun testDictThinkingLevel_WarnsOnly() {
        val settings = base().copy(
            dict = V2DictSettings(enabled = true, providerId = "gemini", model = "gemma-4-31b-it", thinkingLevel = "high")
        )
        val issues = validateV2Settings(settings)
        assertFalse(issues.any { it.blocksSave })
        assertTrue(issues.any { it.message.contains("辞書") && it.message.contains("thinkingLevel") })
    }

    @Test
    fun testUnknownProvider_Blocks() {
        val settings = base().copy(
            profiles = listOf(V2ModelProfile(providerId = "groq", model = "llama"))
        )
        assertTrue(validateV2Settings(settings).any { it.blocksSave })
    }

    @Test
    fun testSplitAndPrevContext_Validation() {
        assertTrue(validateV2Settings(base()).none { it.blocksSave })
        val small = base().copy(split = V2SplitSettings(enabled = true, splitSizeChars = 100))
        assertTrue(validateV2Settings(small).any { it.blocksSave })
        assertEquals(500, coercedV2Settings(small).split.splitSizeChars)
        val lines = base().copy(prevContext = V2PrevContext(enabled = true, lines = 0))
        assertTrue(validateV2Settings(lines).any { it.blocksSave })
        assertEquals(1, coercedV2Settings(lines).prevContext.lines)
    }

    @Test
    fun testPromptOrder_Validation() {
        assertTrue(validateV2Settings(base()).none { it.blocksSave })
        val bad = base().copy(
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", promptOrder = listOf(9)))
        )
        assertTrue(validateV2Settings(bad).any { it.blocksSave })
        val empty = base().copy(
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", promptOrder = emptyList()))
        )
        assertTrue(validateV2Settings(empty).any { it.blocksSave })
    }

    @Test
    fun testSizeRatios_InvertedBlocks() {
        assertTrue(validateV2Settings(base()).none { it.blocksSave })
        val ratiosBad = com.example.novelscraper.translation.v2.settings.V2SizeRatios(
            zhMin = 200, zhMax = 100, koMin = 90, koMax = 150,
            enMin = 105, enMax = 220, jaMin = 100, jaMax = 200
        )
        assertTrue(validateV2Settings(base().copy(sizeRatios = ratiosBad)).any { it.blocksSave })
    }

    @Test
    fun testLegacyImport_SplitAndPrevContext() {
        val importLegacy =
            com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository::importLegacySettings
        val raw = """
        {
          "geminiApiKeys": ["k1"],
          "modelProfiles": [
            {"provider": "GEMINI", "modelName": "gemini-3.5-flash"}
          ],
          "enableTextSplit": true,
          "textSplitSizeChars": 8000,
          "inputEncoding": "SJIS",
          "enablePrevSrcContext": true,
          "prevSrcContextLines": 30
        }
        """.trimIndent()
        val ok = importLegacy(raw)
        assertTrue(ok.settings.split.enabled)
        assertEquals(8000, ok.settings.split.splitSizeChars)
        assertEquals("SJIS", ok.settings.split.inputEncoding)
        assertTrue(ok.settings.prevContext.enabled)
        assertEquals(30, ok.settings.prevContext.lines)
    }
}
