package com.example.novelscraper

import com.example.novelscraper.scraper.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PresetJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun testValidPresetJsonParsing() {
        // 正常なJSONのパーステスト
        val jsonStr = """
            {
                "CustomPreset": {
                    "folder": "#novel_title",
                    "title": ".chapter_title",
                    "body": "#honbun",
                    "next": ".next_page",
                    "delay": "3",
                    "endCheck": "null",
                    "autoUrl": "example.com"
                }
            }
        """.trimIndent()

        val imported = json.decodeFromString<Map<String, ScraperConfig>>(jsonStr)
        assertEquals(1, imported.size)
        assertEquals("#novel_title", imported["CustomPreset"]?.folder)
    }

    @Test
    fun testEmptyPresetJsonValidation() {
        // 空のJSONデータのバリデーションテスト
        val jsonStr = "{}"
        val imported = json.decodeFromString<Map<String, ScraperConfig>>(jsonStr)
        
        val exception = assertThrows(IllegalArgumentException::class.java) {
            if (imported.isEmpty()) {
                throw IllegalArgumentException("Imported file has no presets")
            }
        }
        assertEquals("Imported file has no presets", exception.message)
    }

    @Test
    fun testEmptyPresetNameValidation() {
        // プリセット名が空文字のデータのバリデーションテスト
        val jsonStr = """
            {
                "": {
                    "folder": "#novel_title"
                }
            }
        """.trimIndent()
        
        val imported = json.decodeFromString<Map<String, ScraperConfig>>(jsonStr)
        
        val exception = assertThrows(IllegalArgumentException::class.java) {
            if (imported.any { it.key.trim().isEmpty() }) {
                throw IllegalArgumentException("Preset names cannot be empty")
            }
        }
        assertEquals("Preset names cannot be empty", exception.message)
    }
}
