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

    private fun en() = ResidualOptions(SourceLang.EN)

    @Test
    fun testResidual_EnglishLongBlockFails() {
        val text = "This is a very long English sentence left untranslated in the output " +
            "and it keeps going without any Japanese characters at all."
        val reason = residualFailure(text, en())
        assertNotNull(reason)
        assertTrue(reason!!.contains("latin"))
    }

    @Test
    fun testResidual_EnglishShortHeadingOrProperNounPasses() {
        // 短い章見出しや固有名詞、ゲーム用語は60文字未満のため誤爆せずパス
        val text = "Chapter 1\n勇者は立ち上がった。\nGame Over\n"
        assertNull(residualFailure(text, en()))
    }

    @Test
    fun testResidual_EnglishMixedInJapaneseSentencePasses() {
        // 日本語文の中に混ざる英単語（OK、HPなど）はセグメント内にかな・漢字があるためパス
        val text = "彼女は「OK！」と答えた。\nステータス画面にHPが表示された。\n"
        assertNull(residualFailure(text, en()))
    }

    @Test
    fun testResidual_LatinInZhOrJaSourceSkipped() {
        // ZHやJAソースではLatin残留検査はスキップ
        val text = "This is a very long English sentence left untranslated in the output " +
            "and it keeps going without any Japanese characters at all."
        assertNull(residualFailure(text, zh()))
        assertNull(residualFailure(text, ResidualOptions(SourceLang.JA)))
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

    @Test
    fun testResidual_KanaMixedWithPureSimplifiedHanziFails() {
        // かな（彼は、お前は）が混ざっていても、純粋簡体字（说、这、个）を含む中国語が残留していれば検知される
        val leaked = "彼は冷笑着说道：你这个不知死活的家伙！\n" +
            "主角从一个普通的少年成长为世界的救世主。\n"
        val text = leaked.repeat(2)
        val reason = residualFailure(text, zh())
        assertNotNull(reason)
        assertTrue(reason!!.contains("han"))
    }

    @Test
    fun testResidual_LongJapaneseHeadingPasses() {
        // 純粋簡体字を含まない正当な日本の漢字見出し（14文字）は、本文にかながあれば誤爆せずパスする
        val heading = "第十三章 異世界転生勇者之奮闘記\n"
        val body = "勇者は立ち上がり、仲間たちと共に魔王の城へと向かった。\n"
        val text = heading + body.repeat(5)
        assertNull(residualFailure(text, zh()))
    }

    @Test
    fun testResidual_KoMixedHangulFailsWhenExceedingThreshold() {
        // 文末に「です」があっても、ハングルが累積して閾値を超えれば韓国語残留として検知される
        val leaked = "주인공은 평범한 소년이었고 세계를 구원하기 위해 길을 떠났습니다です。\n"
        val text = leaked.repeat(2)
        val reason = residualFailure(text, ko())
        assertNotNull(reason)
        assertTrue(reason!!.contains("hangul"))
    }

    @Test
    fun testResidual_SimplifiedSingleCountSuffices() {
        // 簡体字は漢字数に含まれるため、二重計上なしでも30字で検知されること
        val leaked = "这是简体中文测试文本。\n".repeat(4)
        val reason = residualFailure(leaked, zh())
        assertNotNull(reason)
        assertTrue(reason!!.contains("han"))
    }
}
