package com.example.novelscraper

import com.example.novelscraper.translation.web.*
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

    @Test
    fun testToggle_containsSingleFlowGuards() {
        val script = LiveTranslateScriptBuilder.buildToggleLiveTranslateScript()
        // 読込失敗の固着防止
        assertTrue(script.contains("__gtWatchdog"))
        assertTrue(script.contains("LOAD_TIMEOUT"))
        assertTrue(script.contains("LOAD_FAILED"))
        assertTrue(script.contains("onerror"))
        assertTrue(script.contains("translate.googleapis.com/translate_a/element.js"))
        // 誤SUCCESS防止
        assertTrue(script.contains("__gtAttemptCombo"))
        assertTrue(script.contains("COMBO_NOT_FOUND"))
        // 連打・stale
        assertTrue(script.contains("BUSY"))
        assertTrue(script.contains("__liveTranslateStartTime"))
        // 世代ガード（旧世代コールバックの混入防止）
        assertTrue(script.contains("__gtGen"))
        assertTrue(script.contains("__gtMyGen"))
        // 事前検査・日本語判定
        assertTrue(script.contains("ALREADY_JA"))
        assertTrue(script.contains("NO_BODY"))
        assertTrue(script.contains("3040"))
        // スクロール保持
        assertTrue(script.contains("__liveTranslateScrollY"))
        assertTrue(script.contains("scrollTo"))
    }

    @Test
    fun testRestore_preservesScrollPosition() {
        val script = LiveTranslateScriptBuilder.buildRestoreScript()
        assertTrue(script.contains("__liveTranslateScrollY"))
        assertTrue(script.contains("scrollTo"))
    }
}
