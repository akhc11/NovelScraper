package com.example.novelscraper

import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslateScriptBuilderTest {

    @Test
    fun testBuildToggleLiveTranslateScript_containsHardenedLogic() {
        val script = LiveTranslateScriptBuilder.buildToggleLiveTranslateScript()
        assertTrue(script.contains("translate.google.com/translate_a/element.js"))
        assertTrue(script.contains("google.translate.TranslateElement"))
        assertTrue(script.contains("googtrans=/auto/ja"))
        assertTrue(script.contains("__liveTranslateActive"))
        assertTrue(script.contains("__originalBodyHtml"))
        assertTrue(script.contains("__liveTranslateInProgress"))
        assertTrue(script.contains("AndroidBridge.onLiveTranslateStatus"))
    }

    @Test
    fun testBuildRestoreScript_containsRestoreLogic() {
        val script = LiveTranslateScriptBuilder.buildRestoreScript()
        assertTrue(script.contains("googtrans=/auto/null"))
        assertTrue(script.contains("__originalBodyHtml"))
        assertTrue(script.contains("AndroidBridge.onLiveTranslateStatus('RESTORED')"))
    }
}
