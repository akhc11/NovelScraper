package com.example.novelscraper

import com.example.novelscraper.translation.v2.pipeline.ResidualOptions
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.pipeline.residualFailure
import com.example.novelscraper.translation.v2.pipeline.verifyTranslation
import org.junit.Assert.*
import org.junit.Test

class V2ResidualTest {

    private fun zh() = ResidualOptions(SourceLang.ZH)
    private fun ko() = ResidualOptions(SourceLang.KO)

    @Test
    fun testResidual_ZhHanOnlyFails() {
        val leaked = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。他在旅途中遇到了很多朋友。"
        val reason = residualFailure(leaked, zh())
        assertNotNull(reason)
        assertTrue(reason!!.contains("han"))
    }

    @Test
    fun testResidual_ShortHeadingInherited() {
        // Short Han-only heading inside kana text: inherits, ignored.
        val text = "第一章決戦\n" + "東海の水と白頭山がすり減るまで神のご加護があり我が国は永遠に栄える。\n".repeat(3)
        assertNull(residualFailure(text, zh()))
    }

    @Test
    fun testResidual_WholeShortPasses() {
        // Below the 30-char floor (parity with the old floor): passes.
        assertNull(residualFailure("第一章決戦", zh()))
    }

    @Test
    fun testResidual_KoHangulFails() {
        val leaked = "주인공은 평범한 소년이었다.\n".repeat(4) +
            "そして仲間たちと共に旅に出ました。\n"
        val reason = residualFailure(leaked, ko())
        assertNotNull(reason)
        assertTrue(reason!!.contains("hangul"))
    }

    @Test
    fun testResidual_MixedKanaPasses() {
        // Kana present in the segment: Japanese, not residue.
        assertNull(residualFailure("勇者は旅に出た。仲間と共に進んだ。", zh()))
        assertNull(residualFailure("주인공은旅に出た。", ko()))
    }

    @Test
    fun testResidual_LatinSkipped() {
        // Latin-only segments never flag (proper nouns; parity with old).
        val text = "This is a very long English sentence left untranslated in the output " +
            "and it keeps going without any Japanese characters at all."
        assertNull(residualFailure(text, ResidualOptions(SourceLang.EN)))
    }

    @Test
    fun testResidual_JaSourceSkipped() {
        val text = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。他在旅途中遇到了很多朋友。"
        assertNull(residualFailure(text, ResidualOptions(SourceLang.JA)))
    }

    @Test
    fun testResidual_SumThreshold() {
        val seg = "昔有一位将军名叫韩信他用兵如神"
        assertNull(residualFailure(seg, zh()))
        assertNotNull(residualFailure("$seg\n$seg\n$seg", zh()))
    }

    @Test
    fun testResidual_Tunable() {
        val seg = "昔有一位将军名叫韩信他用兵如神"
        assertNotNull(residualFailure(seg, ResidualOptions(SourceLang.ZH, minResidueChars = 10)))
    }

    @Test
    fun testVerifyIntegration_ResidualGate() {
        val src = "这是一个关于冒险的故事主人公少年成长救世主旅途朋友敌人親切"
        val dst = "昔有一位将军名叫韩信他用兵如神百战百胜运筹帷幄決勝千里之外天下無双"
        val base = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = true)
        // Gate off: passes (isolates residual from size/kana).
        assertNotNull(verifyTranslation(src, "$dst\n[SRC_END]", base))
        // Gate on: residue fails.
        assertNull(verifyTranslation(src, "$dst\n[SRC_END]", base.copy(residual = zh())))
    }
}
