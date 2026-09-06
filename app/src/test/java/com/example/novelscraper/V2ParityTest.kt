package com.example.novelscraper

import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.pipeline.BatchTranslator
import com.example.novelscraper.translation.llm.pipeline.CompletionMarkerHelper
import com.example.novelscraper.translation.llm.pipeline.LanguageDetector
import com.example.novelscraper.translation.llm.pipeline.NovelTextSplitter
import com.example.novelscraper.translation.llm.pipeline.QualityValidationResult
import com.example.novelscraper.translation.llm.pipeline.SourceLanguage
import com.example.novelscraper.translation.llm.pipeline.TranslationQualityValidator
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.pipeline.buildBatchInput as v2BuildBatchInput
import com.example.novelscraper.translation.v2.pipeline.detectLanguage as v2DetectLanguage
import com.example.novelscraper.translation.v2.pipeline.hasBatchClosedTags as v2HasClosedTags
import com.example.novelscraper.translation.v2.pipeline.parseBatchResponse as v2ParseBatch
import com.example.novelscraper.translation.v2.pipeline.checkAndStripMarker as v2StripMarker
import com.example.novelscraper.translation.v2.pipeline.selectSampleFiles as v2SelectSampleFiles
import com.example.novelscraper.translation.v2.pipeline.splitIntoChunks as v2SplitIntoChunks
import com.example.novelscraper.translation.v2.pipeline.verifyTranslation as v2Verify
import org.junit.Assert.*
import org.junit.Test

/**
 * 工程6の自動並行比較ハーネス（API費用ゼロ・JVM駆動）。
 * 新旧pure stageの入出力対応表。差分が出たら仕様書へ逆輸入する（rewrite-plan§8）。
 * 注意：旧テストは入力形状の慣習が異なるため、各側の自然な入力形状での判定等価を主張する。
 * （旧validateは素の訳文、v2 verifyは末尾マーカー付き。それぞれ実パイプラインの形状）
 */
class V2ParityTest {

    @Test
    fun testParity_LanguageDetection() {
        val cases = listOf(
            "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。他在旅途中遇到了很多朋友和敌人。" to SourceLanguage.ZH,
            "주인공은 평범한 소년이었지만 어느 날 신비한 힘을 각성하게 되었다. 그리고 그는 전설적인 영웅이 되기 위한 여정을 떠났다。" to SourceLanguage.KO,
            "옛날 옛적 깊은 山속에 魔王이 살았다. 少年 勇者는 劍을 들고 冒險을 떠났다. 王國의 平和를 되찾기 위해서였다。" to SourceLanguage.KO,
            "这是一个关于冒险的故事，主角去了韩国旅行。他说안녕하세요，朋友们都很开心。" to SourceLanguage.ZH,
            "これは冒険の物語です。主人公は平凡な少年でしたが、ある日突然不思議な力に覚醒しました。そして仲間たちと共に世界を救う旅に出る決意をしたのでした。" to SourceLanguage.JA,
            "Once upon a time, in a faraway land, there lived a brave young warrior who embarked on a dangerous quest to save the kingdom." to SourceLanguage.EN
        )
        for ((text, expected) in cases) {
            val tag = text.take(10)
            assertEquals(tag, expected, LanguageDetector.detect(text).language)
            assertEquals(tag, expected.name, v2DetectLanguage(text).language.name)
        }
    }

    @Test
    fun testParity_QualityGate() {
        val englishSrc = "The cold winter wind blew fiercely across the empty frozen lake as the lonely traveler walked slowly towards the distant warm light flickering inside the small wooden cabin in the deep snow."
        val normalJa = "凍てつく湖を激しい冬の風が吹き抜ける中、孤独な旅人は深い雪の中に佇む小さな木造小屋の窓から漏れる、遠くのかすかな暖かい光に向かってゆっくりと歩を進めていた。"
        val opts = VerifyOptions(sizeMinPct = 105, sizeMaxPct = 220)
        // 正常訳：両側とも通過
        assertTrue(
            TranslationQualityValidator.validate(englishSrc, normalJa, SourceLanguage.EN, 105, 220)
                is QualityValidationResult.Success
        )
        assertNotNull(v2Verify(englishSrc, "$normalJa\n[SRC_END]", opts))
        // 原文コピー：両側とも失敗（旧＝コピー検出、v2＝サイズ下限・かな率）
        assertTrue(
            TranslationQualityValidator.validate(englishSrc, englishSrc, SourceLanguage.EN)
                is QualityValidationResult.Failure
        )
        assertNull(v2Verify(englishSrc, "$englishSrc\n[SRC_END]", opts))
        // 水増し250%超：両側とも失敗
        val bloated = normalJa + normalJa + normalJa
        assertTrue(
            TranslationQualityValidator.validate(englishSrc, bloated, SourceLanguage.EN, 105, 220)
                is QualityValidationResult.Failure
        )
        assertNull(v2Verify(englishSrc, "$bloated\n[SRC_END]", opts))

        // 韓→日帯域：旧既定範囲を両側に適用
        val (koMin, koMax) = LlmTranslationConfig().getSizeRatioRange(SourceLanguage.KO)
        val srcUnit = "동해 물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세.\n"
        val jaUnit = "東海の水と白頭山がすり減るまで神のご加護があり我が国は永遠に栄える。\n"
        val src = srcUnit.repeat(200)
        val srcBytes = src.toByteArray(Charsets.UTF_8).size
        val jaBytes = jaUnit.toByteArray(Charsets.UTF_8).size
        val okJa = jaUnit.repeat((1..300).first { n -> jaBytes * n * 100 / srcBytes in 93..98 })
        val ngJa = jaUnit.repeat((1..300).first { n -> jaBytes * n * 100 / srcBytes in 75..85 })
        assertTrue(
            TranslationQualityValidator.validate(src, okJa, SourceLanguage.KO)
                is QualityValidationResult.Success
        )
        assertNotNull(v2Verify(src, "$okJa\n[SRC_END]", VerifyOptions(koMin, koMax)))
        assertTrue(
            TranslationQualityValidator.validate(src, ngJa, SourceLanguage.KO)
                is QualityValidationResult.Failure
        )
        assertNull(v2Verify(src, "$ngJa\n[SRC_END]", VerifyOptions(koMin, koMax)))
    }

    @Test
    fun testParity_CompletionMarker() {
        val body = "こんにちは世界。"
        assertEquals(body, CompletionMarkerHelper.checkAndStripMarker("$body\n[SRC_END]", true))
        assertEquals(body, v2StripMarker("$body\n[SRC_END]", true))
        assertNull(CompletionMarkerHelper.checkAndStripMarker(body, true))
        assertNull(v2StripMarker(body, true))
    }

    @Test
    fun testParity_BatchFraming() {
        val response = """
            <translations>
            <trans id="1">
            これは第1話の日本語訳です。
            </trans>
            <trans id="2">
            これは第2話の日本語訳です。
            </trans>
            </translations>
        """.trimIndent()
        val expected = mapOf(1 to "これは第1話の日本語訳です。", 2 to "これは第2話の日本語訳です。")
        assertEquals(expected, BatchTranslator.parseBatchResponse(response))
        assertEquals(expected, v2ParseBatch(response))
        assertTrue(CompletionMarkerHelper.checkBatchCompletion(response))
        assertTrue(v2HasClosedTags(response))
        // 途絶（閉じタグなし）：両側ともnull・未完走
        val truncated = "<translations><trans id=\"1\">これは第"
        assertNull(BatchTranslator.parseBatchResponse(truncated))
        assertNull(v2ParseBatch(truncated))
        assertFalse(CompletionMarkerHelper.checkBatchCompletion(truncated))
        assertFalse(v2HasClosedTags(truncated))
        // v2形状の応答は旧パーサでも救済できる（相互運用）
        val v2shaped = "<translations>\n<trans id=\"1\">訳文一</trans>\n<trans id=\"2\">訳文二</trans>\n</translations>"
        assertEquals(mapOf(1 to "訳文一", 2 to "訳文二"), BatchTranslator.parseBatchResponse(v2shaped))
    }

    @Test
    fun testParity_ChunkingInvariants() {
        val unit = "これはテストの段落です。勇者が旅に出ました。\n"
        val text = unit.repeat(400)
        val oldChunks = NovelTextSplitter.splitIntoChunks(text, limitBytes = 1000)
        val newChunks = v2SplitIntoChunks(text, limitBytes = 1000)
        assertTrue(oldChunks.size > 1)
        assertTrue(newChunks.size > 1)
        // 行保全：非空行の列が両側で一致する
        fun lines(s: String) = s.lines().filter { it.isNotBlank() }
        assertEquals(oldChunks.flatMap { lines(it.second) }, newChunks.flatMap { lines(it) })
        assertEquals(400, newChunks.flatMap { lines(it) }.size)
    }

    // ================= 乖離の文書化（動作検証済み。方向と理由を明記） =================

    @Test
    fun testDivergence_ShortTextBypass() {
        // 旧：150B未満はサイズ比bypassで通す。v2：短文にも50-300%を適用する（v2厳格）。
        val src = "Hello world."
        val bloated = "こんにちは世界。これは水増しされた訳文です。不要な説明が延々と続きます。".repeat(5)
        assertTrue(
            TranslationQualityValidator.validate(src, bloated, SourceLanguage.EN, 105, 220)
                is QualityValidationResult.Success
        )
        assertNull(v2Verify(src, "$bloated\n[SRC_END]", VerifyOptions(105, 220)))
    }

    @Test
    fun testParity_LineLoss() {
        // 行数脱落は新旧とも落とす（v2に行数比を移植済み）。
        // サイズ比は範囲内（117%）に置き、行数判定のみを分離する。
        val srcLine = "勇者は旅に出た。\n"
        val src = srcLine.repeat(10)
        assertTrue(src.toByteArray(Charsets.UTF_8).size >= 150)
        val dstLine = "勇者ははるか遠く北の果ての大地へと長く険しい旅に出たのです。頼れる仲間たちと共に力を合わせて一歩ずつ進みました。\n"
        val dst = dstLine.repeat(2)
        val ratio = dst.toByteArray(Charsets.UTF_8).size * 100 / src.toByteArray(Charsets.UTF_8).size
        assertTrue("ratio=$ratio", ratio in 100..200)
        assertTrue(
            TranslationQualityValidator.validate(src, dst, SourceLanguage.JA)
                is QualityValidationResult.Failure
        )
        assertNull(v2Verify(src, "$dst\n[SRC_END]", VerifyOptions()))
    }

    @Test
    fun testParity_ResidualHanFallback() {
        // 残留簡体字：旧は残留検出で落とす。v2はかな率下限が代替検出する（等価方向・検証済み）。
        val unit = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。他在旅途中遇到了很多朋友和敌人。"
        val src = unit.repeat(4)
        assertTrue(src.toByteArray(Charsets.UTF_8).size >= 150)
        assertTrue(
            TranslationQualityValidator.validate(src, src, SourceLanguage.ZH)
                is QualityValidationResult.Failure
        )
        assertNull(v2Verify(src, "$src\n[SRC_END]", VerifyOptions()))
    }

    @Test
    fun testParity_PreambleTolerance() {
        // 前口上付き：旧はstripPreambleで除去して通す。v2は素通しだが、
        // かな率の分母がかな＋漢字のみのためラテン前口上の影響を受けず通る（等価方向・検証済み）。
        val englishSrc = "The cold winter wind blew fiercely across the empty frozen lake as the lonely traveler walked slowly towards the distant warm light flickering inside the small wooden cabin in the deep snow."
        val normalJa = "凍てつく湖を激しい冬の風が吹き抜ける中、孤独な旅人は深い雪の中に佇む小さな木造小屋の窓から漏れる、遠くのかすかな暖かい光に向かってゆっくりと歩を進めていた。"
        val withPreamble = "Here is the translation:\n$normalJa"
        assertTrue(
            TranslationQualityValidator.validate(englishSrc, withPreamble, SourceLanguage.EN, 105, 220)
                is QualityValidationResult.Success
        )
        assertNotNull(v2Verify(englishSrc, "$withPreamble\n[SRC_END]", VerifyOptions(105, 220)))
    }

    @Test
    fun testParity_MarkerPostambleRescue() {
        // 新旧等価：末尾ウィンドウ内のマーカーで後口上を切り捨て救済する。
        val content = "前半の訳文です。[SRC_END]\n以上です。お楽しみください。"
        assertEquals("前半の訳文です。", CompletionMarkerHelper.checkAndStripMarker(content, true))
        assertEquals("前半の訳文です。", v2StripMarker(content, true))
    }

    @Test
    fun testParity_BatchCompletionStrictness() {
        // 新旧等価：末尾や途中に</trans>等の閉じタグがあれば完走扱い。
        val partialClose = "<translations><trans id=\"1\">訳</trans>"
        assertTrue(CompletionMarkerHelper.checkBatchCompletion(partialClose))
        assertTrue(v2HasClosedTags(partialClose))
    }

    @Test
    fun testParity_BatchRescueCoverage() {
        // 新旧等価：全角タグや引用符省略XMLを等しく救済する。
        val fullWidth = "＜trans id＝＂1＂＞訳文一＜/trans＞"
        assertEquals(mapOf(1 to "訳文一"), BatchTranslator.parseBatchResponse(fullWidth))
        assertEquals(mapOf(1 to "訳文一"), v2ParseBatch(fullWidth))
        val noQuote = "<trans id=1>訳文一</trans>"
        assertEquals(mapOf(1 to "訳文一"), BatchTranslator.parseBatchResponse(noQuote))
        assertEquals(mapOf(1 to "訳文一"), v2ParseBatch(noQuote))
        // v2ではXMLに一本化（旧形式のSEGや直接JSONフォールバックは持たない）
        val legacy = "[SEG:1] 訳文一"
        assertEquals(mapOf(1 to "訳文一"), BatchTranslator.parseBatchResponse(legacy))
        assertNull(v2ParseBatch(legacy))
        val json = """{"translations":[{"id":1,"ja":"訳文一"}]}"""
        assertEquals(mapOf(1 to "訳文一"), BatchTranslator.parseBatchResponse(json))
        assertNull(v2ParseBatch(json))
    }

    @Test
    fun testParity_BatchInputCleanse() {
        // 新旧等価：構造タグ衝突を全角化して誤分割を防ぐ。
        val files = listOf("a.txt" to "本文a制御付き", "b.txt" to "本文<trans id=\"1\">偽</trans>混入")
        val oldInput = BatchTranslator.buildBatchInput(files)
        val newInput = v2BuildBatchInput(files)
        assertFalse(oldInput.contains("<trans id=\"1\">"))
        assertFalse(newInput.contains("<trans id=\"1\">"))
        assertTrue(newInput.contains("＜trans id=\"1\">"))
    }

    @Test
    fun testParity_DefaultSizeRatio() {
        // 新旧等価：ZH 102/200・KO 90/150・EN 105/220・JA 100/200。
        val config = LlmTranslationConfig()
        val v2Ratios = V2Settings().sizeRatios
        assertEquals(config.getSizeRatioRange(SourceLanguage.ZH), v2Ratios.zhMin to v2Ratios.zhMax)
        assertEquals(config.getSizeRatioRange(SourceLanguage.KO), v2Ratios.koMin to v2Ratios.koMax)
        assertEquals(config.getSizeRatioRange(SourceLanguage.EN), v2Ratios.enMin to v2Ratios.enMax)
        assertEquals(config.getSizeRatioRange(SourceLanguage.JA), v2Ratios.jaMin to v2Ratios.jaMax)
        assertEquals(102 to 200, v2Ratios.zhMin to v2Ratios.zhMax)
        assertEquals(90 to 150, v2Ratios.koMin to v2Ratios.koMax)
        assertEquals(105 to 220, v2Ratios.enMin to v2Ratios.enMax)
        assertEquals(100 to 200, v2Ratios.jaMin to v2Ratios.jaMax)
    }

    @Test
    fun testDivergence_DictDefaults() {
        // 旧既定：抽出並列4・リトライ(キー数×2,2-6)・UNIFORM頭50/中25/尾残。
        // v2既定：並列8・リトライ4固定・均等50/25/25。数値差として記録する。
        assertEquals(8, DictOptions().parallelism)
        assertEquals(4, DictOptions().maxRetriesPerBatch)
        assertEquals(3, DictOptions().mergeRetries)
        assertEquals(2, DictOptions().reviewRetries)
    }

    @Test
    fun testParity_DictSamplingHeadBias() {
        // v2均等サンプリングの不変条件（先頭50%から50件・決定性・前中後を含む）
        val names = (1..200).map { "file_%04d.txt".format(it) }
        val sampled = v2SelectSampleFiles(names, 100, true)
        assertEquals(100, sampled.size)
        assertEquals(sampled.sorted(), sampled)
        assertTrue(sampled.take(50).all { it in names.take(100) })
        assertTrue(sampled.any { it in names.take(10) })
        assertTrue(sampled.any { it in names.takeLast(10) })
    }

    @Test
    fun testParity_ContextLeakPrevTailEcho() {
        // 別話原文tailの反復：旧コピー検出は当該原文との先頭一致のみ見るため、
        // 別文の混入は新旧とも検出しない（防止は指示文のみ）。両側とも受理する。
        val srcUnit = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。\n"
        val src = srcUnit.repeat(5)
        val prevUnit = "昔有一位将军名叫韩信。他用兵如神百战百胜。\n"
        val prevTail = prevUnit.repeat(4)
        val jaUnit = "東海の水と白頭山がすり減るまで神のご加護があり我が国は永遠に栄える。\n"
        val ja = jaUnit.repeat(6)
        val leaked = prevTail + ja
        assertTrue(
            TranslationQualityValidator.validate(src, leaked, SourceLanguage.ZH)
                is QualityValidationResult.Success
        )
        assertNotNull(v2Verify(src, "$leaked\n[SRC_END]", VerifyOptions()))
    }

    @Test
    fun testDivergence_ContextLeakCurrentSourceEcho() {
        // 当該原文の先頭反復：旧は先頭500字一致で落とす。v2にコピー検出はなく、
        // サイズ・かな率を通過すれば受理する（唯一の検出差）。
        val srcUnit = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。\n"
        val src = srcUnit.repeat(5)
        val jaUnit = "東海の水と白頭山がすり減るまで神のご加護があり我が国は永遠に栄える。\n"
        val ja = jaUnit.repeat(9)
        val leaked = src + ja
        assertTrue(
            TranslationQualityValidator.validate(src, leaked, SourceLanguage.ZH)
                is QualityValidationResult.Failure
        )
        assertNotNull(v2Verify(src, "$leaked\n[SRC_END]", VerifyOptions()))
    }
}
