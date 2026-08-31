package com.example.novelscraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWebTranslatorTest {

    @Test
    fun testBuildScriptsGenerateValidJs() {
        val extractScript = NativeWebTranslator.buildExtractScript()
        assertTrue(extractScript.contains("window.__ns_nodes"))
        assertTrue(extractScript.contains("shadowRoot"))
        assertTrue(extractScript.contains("AndroidBridge.onExtractTexts"))

        val restoreScript = NativeWebTranslator.buildRestoreScript()
        assertTrue(restoreScript.contains("window.__ns_original"))
        assertTrue(restoreScript.contains("nodeValue = window.__ns_original.get(node)"))

        val sampleJson = """[{"id":0,"text":"こんにちは"},{"id":1,"text":"世界"}]"""
        val applyScript = NativeWebTranslator.buildApplyScript(sampleJson)
        assertTrue(applyScript.contains("JSON.parse"))
        assertTrue(applyScript.contains("node.nodeValue = item.text"))
    }

    @Test
    fun testParseGoogleTranslateResponse() {
        // Google Translate API の典型的なレスポンス形式
        val sampleResponse = """
            [[["こんにちは、世界。","Hello, world.",null,null,10],["これはテストです。","This is a test.",null,null,10]],null,"en",null,null,null,null,[]]
        """.trimIndent()

        val parsed = NativeWebTranslator.parseGoogleTranslateResponse(sampleResponse)
        assertNotNull(parsed)
        assertEquals("こんにちは、世界。これはテストです。", parsed)
    }

    @Test
    fun testApplyScriptEscaping() {
        val jsonWithQuotes = """[{"id":0,"text":"彼は「'Hello'」と言った\n改行"}]"""
        val script = NativeWebTranslator.buildApplyScript(jsonWithQuotes)
        // シングルクォートとバックスラッシュがエスケープされていること
        assertTrue(script.contains("\\'"))
    }
}
