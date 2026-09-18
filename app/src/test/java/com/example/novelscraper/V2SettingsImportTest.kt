package com.example.novelscraper

import org.junit.Assert.*
import org.junit.Test

/**
 * 旧設定JSONの取込検証（DataStore/App側）。中核の V2DomainTest から分離。
 * 技術的根拠1行：取込口は App 側（DataStore）の責務のため、中核モジュールから参照しない。
 */
class V2SettingsImportTest {

    @Test
    fun testLegacyImport_Validation() {
        val importLegacy =
            com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository::importLegacySettings
        // 空・破損は既定値＋警告
        val empty = importLegacy("")
        assertTrue(empty.warnings.isNotEmpty())
        assertEquals(1, empty.settings.profiles.size)
        val broken = importLegacy("{not json")
        assertTrue(broken.warnings.isNotEmpty())

        // 正常系：採用＋除外の分岐
        val raw = """
        {
          "geminiApiKeys": ["k1", "", "k2"],
          "openRouterApiKey": "or-key",
          "modelProfiles": [
            {"id": "1", "provider": "GEMINI", "modelName": "gemini-3.5-flash", "temperature": 0.5, "promptOrder": [1, 1], "maxOutputChars": 20000},
            {"provider": "GROQ", "modelName": "llama-x"},
            {"provider": "GEMINI", "modelName": "  "},
            {"provider": "GEMINI", "modelName": "gemini-3.8-flash", "thinkingLevel": "medium", "topP": 9.9}
          ],
          "enableDictGen": true,
          "dictProvider": "OPENROUTER",
          "dictGeminiModel": "gemini-3.1-flash-lite",
          "dictWorkerCount": 99,
          "parallelWorkers": 99,
          "outputSubDir": ""
        }
        """.trimIndent()
        val ok = importLegacy(raw)
        assertEquals(listOf("k1", "k2"), ok.settings.geminiKeys)
        assertEquals("or-key", ok.settings.openRouterKey)
        assertEquals(2, ok.settings.profiles.size)
        assertEquals("gemini-3.5-flash", ok.settings.profiles[0].model)
        assertEquals(0.5, ok.settings.profiles[0].temperature)
        assertEquals(20000, ok.settings.profiles[0].maxOutputChars)
        assertEquals("gemini-3.8-flash", ok.settings.profiles[1].model)
        assertEquals(9.9, ok.settings.profiles[1].topP)
        assertTrue(ok.settings.dict.enabled)
        assertEquals("openrouter", ok.settings.dict.providerId)
        assertEquals(30, ok.settings.dict.workerCount)
        assertEquals(6, ok.settings.limits.parallelWorkers)
        assertEquals("翻訳完了_LLM", ok.settings.limits.outputSubDir)
        // GROQ除外＋空名除外の警告2件
        assertEquals(2, ok.warnings.size)
    }
}
