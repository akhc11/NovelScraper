package com.example.novelscraper

import com.example.novelscraper.translation.llm.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class LlmPipelineTest {

    @Test
    fun testLanguageDetector_Chinese() {
        val zhText = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。他在旅途中遇到了很多朋友和敌人。"
        val result = LanguageDetector.detect(zhText)
        assertEquals(SourceLanguage.ZH, result.language)
    }

    @Test
    fun testLanguageDetector_Korean() {
        val koText = "주인공은 평범한 소년이었지만 어느 날 신비한 힘을 각성하게 되었다. 그리고 그는 전설적인 영웅이 되기 위한 여정을 떠났다."
        val result = LanguageDetector.detect(koText)
        assertEquals(SourceLanguage.KO, result.language)
    }

    @Test
    fun testLanguageDetector_Japanese() {
        val jaText = "これは冒険の物語です。主人公は平凡な少年でしたが、ある日突然不思議な力に覚醒しました。そして仲間たちと共に世界を救う旅に出る決意をしたのでした。"
        val result = LanguageDetector.detect(jaText)
        assertEquals(SourceLanguage.JA, result.language)
    }

    @Test
    fun testLanguageDetector_English() {
        val enText = "Once upon a time, in a faraway land, there lived a brave young warrior who embarked on a dangerous quest to save the kingdom."
        val result = LanguageDetector.detect(enText)
        assertEquals(SourceLanguage.EN, result.language)
    }

    @Test
    fun testCompletionMarkerHelper() {
        val src = "Hello world."
        val withMarker = CompletionMarkerHelper.appendMarker(src, true)
        assertTrue(withMarker.endsWith("[SRC_END]"))

        val outputNormal = "こんにちは世界。\n[SRC_END]"
        val stripped = CompletionMarkerHelper.checkAndStripMarker(outputNormal, true)
        assertEquals("こんにちは世界。", stripped)
        assertFalse(stripped?.contains("[SRC_END]") ?: true)

        val outputWithMarkdown = "```\nこんにちは世界。\n[SRC_END]\n```"
        val strippedMd = CompletionMarkerHelper.checkAndStripMarker(outputWithMarkdown, true)
        assertEquals("```\nこんにちは世界。", strippedMd)
        assertFalse(strippedMd?.contains("[SRC_END]") ?: true)

        val outputCutoff = "こんにちは世界。"
        val failed = CompletionMarkerHelper.checkAndStripMarker(outputCutoff, true)
        assertNull(failed)
    }

    @Test
    fun testTranslationQualityValidator_CopyDetection() {
        val src = "This is an original English sentence."
        val copy = "This is an original English sentence."
        val res = TranslationQualityValidator.validate(src, copy, SourceLanguage.EN)
        assertTrue(res is QualityValidationResult.Failure)
        assertTrue((res as QualityValidationResult.Failure).reason.contains("コピー"))
    }

    @Test
    fun testTranslationQualityValidator_PreambleStripping() {
        val withPreamble = "Certainly! Here is the translation:\nこれは翻訳された日本語の本文です。"
        val stripped = TranslationQualityValidator.stripPreamble(withPreamble)
        assertEquals("これは翻訳された日本語の本文です。", stripped)
    }

    @Test
    fun testBatchTranslator_BuildAndParse() {
        val files = listOf(
            "file1.txt" to "第1話の文章です。",
            "file2.txt" to "第2話の文章です。"
        )

        val batchInput = BatchTranslator.buildBatchInput(files, enableCompletionMarker = true)
        assertTrue(batchInput.contains("[SEG:1]"))
        assertTrue(batchInput.contains("[SEG:2]"))
        assertTrue(batchInput.contains("[SRC_END]"))

        val mockResponse = """
            [SEG:1]
            これは第1話の日本語訳です。
            [SEG:2]
            これは第2話の日本語訳です。
            [SRC_END]
        """.trimIndent()

        val stripped = CompletionMarkerHelper.checkAndStripMarker(mockResponse, true)
        assertNotNull(stripped)

        val parsed = BatchTranslator.parseBatchResponse(stripped!!, 2)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("これは第1話の日本語訳です。", parsed[1])
        assertEquals("これは第2話の日本語訳です。", parsed[2])
    }

    @Test
    fun testNovelTextSplitter() {
        val sb = StringBuilder()
        for (i in 1..200) {
            sb.append("これは段落番号 $i のテスト文章です。十分な長さを持たせます。\n\n")
        }
        val chunks = NovelTextSplitter.splitIntoChunks(sb.toString(), limitBytes = 1000)
        assertTrue(chunks.size > 1)
        assertEquals("part_0001.txt", chunks[0].first)
        assertEquals("part_0002.txt", chunks[1].first)
    }

    @Test
    fun testTranslationQualityValidator_ResidualLang() {
        val src = "这是一个关于冒险的故事。主角从一个普通的少年成长为救世主。他在旅途中遇到了很多朋友和敌人。这里有大量的句子。"
        val zhResidual = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。他在旅途中遇到了很多朋友和敌人。这里有大量的中文句子残留完全没有被翻译成日语。"
        val res = TranslationQualityValidator.validate(src, zhResidual, SourceLanguage.ZH)
        assertTrue(res is QualityValidationResult.Failure)
        assertTrue((res as QualityValidationResult.Failure).reason.contains("中国語") || (res as QualityValidationResult.Failure).reason.contains("簡体字"))
    }

    @Test
    fun testTranslationQualityValidator_SimplifiedChineseDirectDetection() {
        // 日本語文の中に中国語簡体字（这、们、话等）が3文字以上混入しているケース
        val src = "他站了起来，拿起了那把剑。这是一个关于测试的句子。"
        val mixedText = "彼は立ち上がり、その剣を手にした。这是一个关于测试の文です。"
        val res = TranslationQualityValidator.validate(src, mixedText, SourceLanguage.ZH)
        assertTrue(res is QualityValidationResult.Failure)
        assertTrue((res as QualityValidationResult.Failure).reason.contains("簡体字"))
    }

    @Test
    fun testDictionaryJson_WithPreambleAndPostamble() {
        val raw = """
            Here is the dictionary you requested:
            ```json
            {
              "style": "カタカナ",
              "characters": {
                "张伟": "チャン・ウェイ",
                "李娜": "リ・ナ"
              }
            }
            ```
            Hope this helps with your translation!
        """.trimIndent()
        val dict = NovelDictionaryGenerator.parseDictionaryJson(raw)
        assertNotNull(dict)
        assertEquals("カタカナ", dict!!.style)
        assertEquals(2, dict.characters.size)
        assertEquals("チャン・ウェイ", dict.characters["张伟"])
        assertTrue(dict.genders.isEmpty()) // 後方互換性確認
    }

    @Test
    fun testDictionaryJson_WithGenders() {
        val raw = """
            ```json
            {
              "style": "ハイブリッド",
              "characters": {
                "오한성": "オ・ハンソン",
                "양은하": "ヤン・ウナ"
              },
              "genders": {
                "오한성": "男",
                "양은하": "女"
              }
            }
            ```
        """.trimIndent()
        val dict = NovelDictionaryGenerator.parseDictionaryJson(raw)
        assertNotNull(dict)
        assertEquals("ハイブリッド", dict!!.style)
        assertEquals(2, dict.characters.size)
        assertEquals("オ・ハンソン", dict.characters["오한성"])
        assertEquals("男", dict.genders["오한성"])
        assertEquals("女", dict.genders["양은하"])
    }

    @Test
    fun testPromptBuilder_WithDictionaryGenders() {
        val prompt = com.example.novelscraper.translation.llm.prompt.PromptBuilder.buildPrompt(
            promptNumber = 1,
            sourceText = "오한성과 양은하가 대화를 나눴다.",
            dictionaryStyle = "ハイブリッド",
            dictionaryMap = mapOf("오한성" to "オ・ハンソン", "양은하" to "ヤン・ウナ"),
            dictionaryGenders = mapOf("오한성" to "男", "양은하" to "女"),
            enableCompletionMarker = false
        )
        assertTrue(prompt.contains("・오한성 → オ・ハンソン (性別: 男)"))
        assertTrue(prompt.contains("・양은하 → ヤン・ウナ (性別: 女)"))
        assertTrue(prompt.contains("性別（男/女）に応じた自然な日本語表現"))
    }

    @Test
    fun testPromptBuilder_WithoutMatchedGender() {
        val prompt = com.example.novelscraper.translation.llm.prompt.PromptBuilder.buildPrompt(
            promptNumber = 1,
            sourceText = "바람이 차갑게 불어왔다.",
            dictionaryStyle = "カタカナ",
            dictionaryMap = mapOf("오한성" to "オ・ハンソン"),
            dictionaryGenders = mapOf("오한성" to "男"),
            enableCompletionMarker = false
        )
        assertFalse(prompt.contains("性別（男/女）に応じた自然な日本語表現"))
    }

    @Test
    fun testEffectiveSplitThreshold_AutoAndManual() {
        val config = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(
            enableAutoLanguageSize = true,
            langSplitKoreanKb = 25,
            langSplitChineseKb = 20,
            langSplitEnglishKb = 15
        )
        val profile = com.example.novelscraper.translation.llm.engine.ModelProfile(modelName = "gemini-3.5-flash", splitThresholdBytes = 13000)

        // 自動サイズ有効時
        assertEquals(25000, config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, profile))
        assertEquals(20000, config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, profile))
        assertEquals(15000, config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, profile))

        // 自動サイズ無効時 (手動固定)
        val manualConfig = config.copy(enableAutoLanguageSize = false)
        assertEquals(13000, manualConfig.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, profile))
        assertEquals(13000, manualConfig.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, profile))
        assertEquals(13000, manualConfig.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, profile))
    }

    @Test
    fun testEffectivePromptOrder_AutoAndManual() {
        // 成人向け (4, 7) を手動設定したプロファイル
        val nsfwProfile = com.example.novelscraper.translation.llm.engine.ModelProfile(modelName = "gemini-3.5-flash", promptOrder = listOf(4, 7))

        // 1. 自動選択OFF (デフォルト): 手動設定 (4, 7) が100%最優先されること
        val defaultManualConfig = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(enableAutoPromptOrder = false)
        assertEquals(listOf(4, 7), defaultManualConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, nsfwProfile))
        assertEquals(listOf(4, 7), defaultManualConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, nsfwProfile))
        assertEquals(listOf(4, 7), defaultManualConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, nsfwProfile))

        // 2. 自動選択ON (デフォルト値): 言語に応じた最適プロンプトに自動切替されること
        val autoConfig = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(enableAutoPromptOrder = true)
        assertEquals(listOf(3, 7), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, nsfwProfile))
        assertEquals(listOf(1, 1), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, nsfwProfile))
        assertEquals(listOf(2, 7), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, nsfwProfile))

        // 3. 自動選択ON (ユーザーがカスタマイズした順序): ユーザー設定値が適用されること
        val customAutoConfig = autoConfig.copy(
            autoPromptOrderKorean = listOf(3, 1),
            autoPromptOrderChinese = listOf(1, 6, 7),
            autoPromptOrderEnglish = listOf(2, 1)
        )
        assertEquals(listOf(3, 1), customAutoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, nsfwProfile))
        assertEquals(listOf(1, 6, 7), customAutoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, nsfwProfile))
        assertEquals(listOf(2, 1), customAutoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, nsfwProfile))
    }

    @Test
    fun testTextCharsetDetector_BOMAndFallback() {
        val utf8Bytes = "こんにちは世界".toByteArray(Charsets.UTF_8)
        val decodedUtf8 = TextCharsetDetector.decodeBytes(utf8Bytes)
        assertEquals("こんにちは世界", decodedUtf8)

        // UTF-8 with BOM
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8Bytes
        val decodedBom = TextCharsetDetector.decodeBytes(bomBytes)
        assertEquals("こんにちは世界", decodedBom)

        // Shift_JIS bytes
        val sjisBytes = "日本語テスト".toByteArray(java.nio.charset.Charset.forName("Shift_JIS"))
        val decodedSjis = TextCharsetDetector.decodeBytes(sjisBytes)
        assertEquals("日本語テスト", decodedSjis)
    }

    @Test
    fun testTextCleanser_ControlChars() {
        val dirty = "Hello\u0000World!\u200B\u0007This is a\ttest\nline."
        val cleaned = TextCleanser.cleanse(dirty)
        assertEquals("HelloWorld!This is a\ttest\nline.", cleaned)
    }

    @Test
    fun testBatchTranslator_FlexibleRegex() {
        val response = """
            [SEG: 1]
            これはセグメント1の訳文です。

            【SEG:2】
            これはセグメント2の訳文です。

            **[SEG:3]**
            これはセグメント3の訳文です。
        """.trimIndent()

        val parsed = BatchTranslator.parseBatchResponse(response, 3)
        assertNotNull(parsed)
        assertEquals("これはセグメント1の訳文です。", parsed!![1])
        assertEquals("これはセグメント2の訳文です。", parsed[2])
        assertEquals("これはセグメント3の訳文です。", parsed[3])
    }

    @Test
    fun testFileClaimManager_ConcurrentClaims() = kotlinx.coroutines.runBlocking {
        val manager = FileClaimManager()
        val claim1 = manager.tryClaimFile("FolderA", "file1.txt")
        val claim2 = manager.tryClaimFile("FolderA", "file1.txt")
        val claim3 = manager.tryClaimFile("FolderA", "file2.txt")

        assertTrue(claim1)
        assertFalse(claim2) // 重複クレームは拒否
        assertTrue(claim3)

        manager.clear()
        val claimAfterClear = manager.tryClaimFile("FolderA", "file1.txt")
        assertTrue(claimAfterClear)
    }

    @Test
    fun testApiKeyPoolManager_ExclusiveClaims() = kotlinx.coroutines.runBlocking {
        val keys = listOf("key-1", "key-2")
        val pool = com.example.novelscraper.translation.llm.rotation.ApiKeyPoolManager(keys)

        val claim1 = pool.claimNewKey()
        assertNotNull(claim1)
        assertEquals(0, claim1!!.first)
        assertEquals("key-1", claim1.second)

        val claim2 = pool.claimNewKey()
        assertNotNull(claim2)
        assertEquals(1, claim2!!.first)
        assertEquals("key-2", claim2.second)

        // 全キー専有後の要求は null
        val claim3 = pool.claimNewKey()
        assertNull(claim3)

        pool.reset()
        val claimAfterReset = pool.claimNewKey()
        assertNotNull(claimAfterReset)
        assertEquals(0, claimAfterReset!!.first)
    }

    @Test
    fun testTranslationQualityValidator_ValidJapanese() {
        val src = "这是一个关于勇者的故事。他为了拯救王国踏上了旅程。"
        val translation = "これは勇者に関する物語です。彼は王国を救うために旅に出ました。"
        val res = TranslationQualityValidator.validate(src, translation, SourceLanguage.ZH)
        assertTrue(res is QualityValidationResult.Success)
    }
}