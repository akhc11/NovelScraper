package com.example.novelscraper

import com.example.novelscraper.translation.llm.pipeline.*
import androidx.documentfile.provider.DocumentFile
import java.io.File
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
    fun testTranslationQualityValidator_CustomSizeRatio() {
        val config = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(
            sizeRatioEnMin = 105,
            sizeRatioEnMax = 220
        )
        val (minR, maxR) = config.getSizeRatioRange(SourceLanguage.EN)
        assertEquals(105, minR)
        assertEquals(220, maxR)

        // 200バイトの英文テキスト
        val englishSrc = "The cold winter wind blew fiercely across the empty frozen lake as the lonely traveler walked slowly towards the distant warm light flickering inside the small wooden cabin in the deep snow."
        val srcBytes = englishSrc.toByteArray(Charsets.UTF_8).size
        assertTrue(srcBytes >= 150)

        // 正常な日本語翻訳 (約1.5倍のバイト数)
        val normalJapanese = "凍てつく湖を激しい冬の風が吹き抜ける中、孤独な旅人は深い雪の中に佇む小さな木造小屋の窓から漏れる、遠くのかすかな暖かい光に向かってゆっくりと歩を進めていた。"
        val okRes = TranslationQualityValidator.validate(englishSrc, normalJapanese, SourceLanguage.EN, minR, maxR)
        assertTrue(okRes is QualityValidationResult.Success)

        // 極端な水増し・ハルシネーション (250% 超過)
        val bloatedJapanese = normalJapanese + normalJapanese + normalJapanese
        val failRes = TranslationQualityValidator.validate(englishSrc, bloatedJapanese, SourceLanguage.EN, minR, maxR)
        assertTrue(failRes is QualityValidationResult.Failure)
        assertTrue((failRes as QualityValidationResult.Failure).reason.contains("サイズ比超過"))
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

        val batchInput = BatchTranslator.buildBatchInput(files)
        assertTrue(batchInput.contains("<doc id=\"1\">"))
        assertTrue(batchInput.contains("<doc id=\"2\">"))
        assertTrue(batchInput.contains("</documents>"))
        assertFalse(batchInput.contains("[SRC_END]"))

        val mockResponse = """
            <translations>
            <trans id="1">
            これは第1話の日本語訳です。
            </trans>
            <trans id="2">
            これは第2話の日本語訳です。
            </trans>
            </translations>
        """.trimIndent()

        assertTrue(CompletionMarkerHelper.checkBatchCompletion(mockResponse, true))

        val parsed = BatchTranslator.parseBatchResponse(mockResponse, 2)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("これは第1話の日本語訳です。", parsed[1])
        assertEquals("これは第2話の日本語訳です。", parsed[2])
    }

    @Test
    fun testBatchTranslator_XmlParsing_Variations() {
        val response = """
            <TRANSLATIONS>
            <trans id="１">
            全角数字IDの訳文です。
            </trans>
            <trans id=2>
            クォートなしIDの訳文です。
            </trans>
            <TRANS ID = "3" >
            大文字タグの訳文です。
            </TRANS>
            </TRANSLATIONS>
        """.trimIndent()

        val parsed = BatchTranslator.parseBatchResponse(response, 3)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("全角数字IDの訳文です。", parsed[1])
        assertEquals("クォートなしIDの訳文です。", parsed[2])
        assertEquals("大文字タグの訳文です。", parsed[3])
    }

    @Test
    fun testBatchTranslator_PartialSalvage() {
        // id=2 が欠落しても 1 と 3 は回収できること（出現順の割当て直しはしない）
        val response = """
            <translations>
            <trans id="1">
            第1話の訳文です。
            </trans>
            <trans id="3">
            第3話の訳文です。
            </trans>
            </translations>
        """.trimIndent()

        val parsed = BatchTranslator.parseBatchResponse(response, 3)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("第1話の訳文です。", parsed[1])
        assertNull(parsed[2])
        assertEquals("第3話の訳文です。", parsed[3])
    }

    @Test
    fun testBatchTranslator_TruncatedResponse() {
        // id=2 の途中で途絶しても、完成している id=1 を救出できること
        // （閉じたセグメントが末尾400文字にあれば完走扱いで部分回収へ進む）
        val response = """
            <translations>
            <trans id="1">
            第1話の訳文です。
            </trans>
            <trans id="2">
            第2話の訳文の途中ま
        """.trimIndent()

        assertTrue(CompletionMarkerHelper.checkBatchCompletion(response, true))

        val parsed = BatchTranslator.parseBatchResponse(response, 2)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("第1話の訳文です。", parsed[1])

        // 閉じタグが一つもない途絶は完走失敗 → 次のプロンプトへ回す
        val earlyCutoff = "<translations>\n<trans id=\"1\">\n第1話の訳文の途中ま"
        assertFalse(CompletionMarkerHelper.checkBatchCompletion(earlyCutoff, true))
        assertNull(BatchTranslator.parseBatchResponse(earlyCutoff, 2))
    }

    @Test
    fun testBatchTranslator_ContentEscaping() {
        // 原文に構造タグ酷似文字列が含まれても誤分割しないこと
        val files = listOf(
            "file1.txt" to "彼は<trans id=\"9\">という札を見た。",
            "file2.txt" to "通常の第2話の文章です。"
        )
        val batchInput = BatchTranslator.buildBatchInput(files)
        assertFalse(batchInput.contains("<trans id=\"9\">"))
        assertTrue(batchInput.contains("＜trans"))

        // 無関係な `<` は温存されること
        val mathText = BatchTranslator.escapeStructuralTags("a<b の比較と<transformer>という語")
        assertTrue(mathText.contains("a<b"))
        assertTrue(mathText.contains("<transformer>"))
    }

    @Test
    fun testBatchTranslator_JsonParsing() {
        val jsonResponse = """
            {"translations": [{"id": 1, "ja": "第1話の訳文です。"}, {"id": 2, "ja": "第2話の訳文です。"}]}
        """.trimIndent()

        val parsed = BatchTranslator.parseBatchResponse(jsonResponse, 2)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("第1話の訳文です。", parsed[1])
        assertEquals("第2話の訳文です。", parsed[2])

        // 壊れたJSON（途絶）は null になること
        val brokenJson = """{"translations": [{"id": 1, "ja": "途中ま"""
        assertNull(BatchTranslator.parseJsonResponse(brokenJson))

        // id欠落の要素は飛ばし、正常分だけ返すこと
        val partialJson = """{"translations": [{"id": 1, "ja": "第1話。"}, {"ja": "IDなし。"}]}"""
        val partial = BatchTranslator.parseJsonResponse(partialJson)
        assertNotNull(partial)
        assertEquals(1, partial!!.size)
    }

    @Test
    fun testBatchTranslator_JsonSchemaShape() {
        val schema = BatchTranslator.buildBatchJsonSchema()
        val obj = schema.toString()
        assertTrue(obj.contains("translations"))
        assertTrue(obj.contains("\"id\""))
        assertTrue(obj.contains("\"ja\""))
    }

    @Test
    fun testCompletionMarkerHelper_BatchVsLargeFile() {
        // バッチ完走判定は閉じタグで行い、大ファイルの [SRC_END] と干渉しないこと
        assertTrue(CompletionMarkerHelper.checkBatchCompletion("<translations><trans id=\"1\">訳</trans></translations>", true))
        assertTrue(CompletionMarkerHelper.checkBatchCompletion("```\n<translations>\n<trans id=\"1\">\n訳\n</trans>\n</translations>\n```", true))
        assertFalse(CompletionMarkerHelper.checkBatchCompletion("<translations><trans id=\"1\">訳", true))
        assertFalse(CompletionMarkerHelper.checkBatchCompletion("", true))
        // 無効化時は常に true
        assertTrue(CompletionMarkerHelper.checkBatchCompletion("anything", false))

        // 大ファイル用 [SRC_END] 検証は従来通り
        assertNotNull(CompletionMarkerHelper.checkAndStripMarker("訳文。\n[SRC_END]", true))
        assertNull(CompletionMarkerHelper.checkAndStripMarker("訳文。", true))
    }

    @Test
    fun testPromptBuilder_BatchXmlAndJson() {
        val xmlPrompt = com.example.novelscraper.translation.llm.prompt.PromptBuilder.buildBatchPrompt(
            promptNumber = 1,
            fileCount = 2
        )
        assertTrue(xmlPrompt.contains("<translations>"))
        assertTrue(xmlPrompt.contains("<trans id=\"1\">"))
        assertFalse(xmlPrompt.contains("[SRC_END]"))
        assertFalse(xmlPrompt.contains("[SEG:1]"))

        val jsonPrompt = com.example.novelscraper.translation.llm.prompt.PromptBuilder.buildBatchPrompt(
            promptNumber = 1,
            fileCount = 2,
            jsonMode = true
        )
        assertTrue(jsonPrompt.contains("\"translations\""))
        assertFalse(jsonPrompt.contains("[SRC_END]"))
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
    fun testNovelTextSplitter_OrphanTailMerge() {
        // 約1000バイトの文章の末尾に、たった1行だけがはみ出しているケース
        val mainText = (1..15).joinToString("\n\n") { "これは段落番号 $it の通常のテスト文章です。十分な長さがあります。" }
        val tinyTail = "（完）" // たった1行の短い末尾
        val fullText = "$mainText\n\n$tinyTail"

        val chunks = NovelTextSplitter.splitIntoChunks(fullText, limitBytes = 1000)
        // 最後のチャンクが「（完）」だけで孤立せず、直前のチャンク末尾に含まれていることを検証
        val lastChunkText = chunks.last().second
        assertTrue(lastChunkText.contains("（完）"))
        // 最後のチャンクが十分な長さを持っている（1行だけのゴミチャンクになっていない）
        assertTrue(lastChunkText.length > 50)
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
        // かなを含まない純粋な中国語残留は失敗 (漢字単体では落とさない)
        val src = "他在旅途中遇到了很多朋友。王国的命运掌握在他的手中。勇者为了拯救世界踏上了漫长的旅程。"
        val pureResidual = "他站了起来，拿起了那把剑。这是一个关于测试的句子。主角从普通的少年成长为救世主。"
        val res = TranslationQualityValidator.validate(src, pureResidual, SourceLanguage.ZH)
        assertTrue(res is QualityValidationResult.Failure)
        assertTrue((res as QualityValidationResult.Failure).reason.contains("中国語"))

        // かなを十分含む日本語訳は常用漢字 (昨/座/治/医等) が混じっても成功
        val japaneseWithJoyoKanji = "昨日の座席で治療を受けた後、病院の医師に相談して油を買いに行き、馬に乗って姿を消した紫の龍を見た。"
        val okRes = TranslationQualityValidator.validate(src, japaneseWithJoyoKanji, SourceLanguage.ZH)
        assertTrue(okRes is QualityValidationResult.Success)
    }

    @Test
    fun testTranslationQualityValidator_PureSimplifiedKanaGate() {
        val src = "他在旅途中遇到了很多朋友。王国的命运掌握在他的手中。勇者为了拯救世界踏上了漫长的旅程。"
        // 純粋簡体字が多く、かなが極端に少ない混在文は失敗 (かな率ゲート)
        val lowKanaMixed = "这是关于冒险的故事。听说他买了书，还发现了问题。长话短说，为了荣誉而战。の"
        val failRes = TranslationQualityValidator.validate(src, lowKanaMixed, SourceLanguage.ZH)
        assertTrue(failRes is QualityValidationResult.Failure)
        assertTrue((failRes as QualityValidationResult.Failure).reason.contains("簡体字"))

        // 同じく純粋簡体字を含んでも、かなが十分あれば素通りする
        val kanaRichMixed = "这是关于冒险的故事说话们现过。彼は立ち上がり、その剣を手にして戦いました。主角は勇者です。"
        assertTrue(TranslationQualityValidator.validate(src, kanaRichMixed, SourceLanguage.ZH) is QualityValidationResult.Success)
    }

    @Test
    fun testTranslationQualityValidator_WebNovelFrequentChars() {
        val src = "他在旅途中遇到了很多朋友。王国的命运掌握在他的手中。勇者为了拯救世界踏上了漫长的旅程。"
        // 剑, 长, 气 が混入し、かな率が極端に低い文章
        val webNovelResidual = "飞剑破空而出，长老深吸一口真气。长剑凌厉，剑气纵横。长话短说，剑法通神。の"
        val res = TranslationQualityValidator.validate(src, webNovelResidual, SourceLanguage.ZH)
        assertTrue(res is QualityValidationResult.Failure)
        assertTrue((res as QualityValidationResult.Failure).reason.contains("簡体字"))
    }

    @Test
    fun testTranslationQualityValidator_CodeFenceAndPostambleStripping() {
        // コードフェンス包みは剥離される
        val fenced = "```\nこれは翻訳された日本語の本文です。\n```"
        assertEquals("これは翻訳された日本語の本文です。", TranslationQualityValidator.stripPreamble(fenced))

        // 末尾の後口上は除去される
        val withPostamble = "これは翻訳された日本語の本文です。\n以上です。"
        assertEquals("これは翻訳された日本語の本文です。", TranslationQualityValidator.stripPreamble(withPostamble))

        // 中国語の前口上は除去される
        val zhPreamble = "以下是翻译：\nこれは翻訳された日本語の本文です。"
        assertEquals("これは翻訳された日本語の本文です。", TranslationQualityValidator.stripPreamble(zhPreamble))

        // 英語の前口上 + 空行挟みも除去される
        val enPreamble = "\nHere is the translation:\nこれは翻訳された日本語の本文です。"
        assertEquals("これは翻訳された日本語の本文です。", TranslationQualityValidator.stripPreamble(enPreamble))
    }

    @Test
    fun testTranslationQualityValidator_LineLossDetection() {
        // 30行の原文が3行に潰れたら改行消失で失敗
        val src = (1..30).joinToString("\n") { "原文の第${it}行目です。" }
        val collapsed = "訳文1行目。\n訳文2行目。\n訳文3行目。"
        val res = TranslationQualityValidator.validate(src, collapsed, SourceLanguage.ZH)
        assertTrue(res is QualityValidationResult.Failure)
        assertTrue((res as QualityValidationResult.Failure).reason.contains("改行"))

        // 行数とサイズ比が保たれていれば成功 (約158%でZH上限200%以内)
        val kept = (1..30).joinToString("\n") { "訳文の第${it}行目です。物語が続く。" }
        assertTrue(TranslationQualityValidator.validate(src, kept, SourceLanguage.ZH) is QualityValidationResult.Success)
    }

    @Test
    fun testTranslationQualityValidator_EnBoundary220() {
        // EN 220% 境界の確認 (220% が正規)
        val englishSrc = "The cold winter wind blew fiercely across the empty frozen lake as the lonely traveler walked slowly towards the distant warm light flickering inside the small wooden cabin in the deep snow."
        val srcBytes = englishSrc.toByteArray(Charsets.UTF_8).size
        assertTrue(srcBytes >= 150)

        // 210% 程度の良訳は成功
        val normalJapanese = "凍てつく湖を激しい冬の風が吹き抜ける中、孤独な旅人は深い雪の中に佇む小さな木造小屋の窓から漏れる、遠くのかすかな暖かい光に向かってゆっくりと歩を進めていた。"
        val ratio = normalJapanese.toByteArray(Charsets.UTF_8).size * 100 / srcBytes
        assertTrue(ratio <= 220)
        assertTrue(TranslationQualityValidator.validate(englishSrc, normalJapanese, SourceLanguage.EN) is QualityValidationResult.Success)
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
    fun testEffectiveSplitThreshold_ReverseCalculatedFromTargetOutputChars() {
        val config = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig()
        // 目標出力 20,000文字 (約60KBの日本語出力)
        val profile20k = com.example.novelscraper.translation.llm.engine.ModelProfile(
            modelName = "gemini-3.5-flash",
            maxOutputChars = 20000
        )

        // 中国語 (ZH: 1.6倍膨張) ➔ 60,000B / 1.6 = 37,500B
        val zhThreshold = config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, profile20k)
        assertEquals(37500, zhThreshold)
        assertEquals((37500 * 0.9).toInt(), config.getEffectiveChunkSize(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, profile20k))

        // 韓国語 (KO: 1.1倍膨張) ➔ 60,000B / 1.1 = 54,545B
        val koThreshold = config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, profile20k)
        assertEquals(54545, koThreshold)

        // 英語 (EN: 1単語5Bで日本語2.8文字) ➔ 20,000 / 2.8 * 5.0 = 35,714B
        val enThreshold = config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, profile20k)
        assertEquals(35714, enThreshold)

        // 目標出力 10,000文字 (約30KBの日本語出力)
        val profile10k = com.example.novelscraper.translation.llm.engine.ModelProfile(
            modelName = "gemma-4-31b-it",
            maxOutputChars = 10000
        )
        // 中国語 ➔ 30,000B / 1.6 = 18,750B
        assertEquals(18750, config.getEffectiveSplitThreshold(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, profile10k))
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
    fun testEffectivePromptOrder_ModelCustomOverride() {
        // 通常モデル (共通従属: useCustomPromptOrder = false)
        val normalProfile = com.example.novelscraper.translation.llm.engine.ModelProfile(
            modelName = "gemini-3.5-flash",
            promptOrder = listOf(1, 1),
            useCustomPromptOrder = false
        )

        // 特殊モデル (成人向け個別固定・保護: useCustomPromptOrder = true)
        val nsfwProtectedProfile = com.example.novelscraper.translation.llm.engine.ModelProfile(
            modelName = "gemma-4-31b-it",
            promptOrder = listOf(4, 7),
            useCustomPromptOrder = true
        )

        // 言語自動選択がONの場合
        val autoConfig = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(enableAutoPromptOrder = true)

        // 1. 通常モデルは言語設定（韓: 3, 7 / 中: 1, 1）に従う
        assertEquals(listOf(3, 7), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, normalProfile))
        assertEquals(listOf(1, 1), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, normalProfile))

        // 2. 個別保護モデルは、言語自動選択がONでも上書きされず、固有設定 (4, 7) が100%最優先される！
        assertEquals(listOf(4, 7), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.KO, nsfwProtectedProfile))
        assertEquals(listOf(4, 7), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.ZH, nsfwProtectedProfile))
        assertEquals(listOf(4, 7), autoConfig.getEffectivePromptOrder(com.example.novelscraper.translation.llm.pipeline.SourceLanguage.EN, nsfwProtectedProfile))

        // 3. 空リスト入力時の安全フォールバック検証 (デフォルト [1, 1] が保証されること)
        val emptyListProfile = normalProfile.copy(promptOrder = emptyList())
        val sanitized = emptyListProfile.copy(promptOrder = emptyListProfile.promptOrder.ifEmpty { listOf(1, 1) })
        assertEquals(listOf(1, 1), sanitized.promptOrder)
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
    fun testFileClaimManager_ConcurrentClaims() {
        val manager = FileClaimManager()
        val claim1 = manager.tryClaimFile("FolderA", "file1.txt")
        val claim2 = manager.tryClaimFile("FolderA", "file1.txt")
        val claim3 = manager.tryClaimFile("FolderA", "file2.txt")

        assertTrue(claim1)
        assertFalse(claim2) // 重複クレームは拒否
        assertTrue(claim3)

        // 解放後は再取得可能 (読み失敗・バッチbreak時のリーク防止)
        manager.releaseFile("FolderA", "file1.txt")
        val claimAfterRelease = manager.tryClaimFile("FolderA", "file1.txt")
        assertTrue(claimAfterRelease)

        // withClaim は二重取得を拒否し、block 終了後に自動解放する
        val (secondOk, _) = manager.withClaim("FolderA", "file1.txt") { "x" }
        assertFalse(secondOk)
        manager.releaseFile("FolderA", "file1.txt")
        val (ok, value) = manager.withClaim("FolderA", "file1.txt") { "done" }
        assertTrue(ok)
        assertEquals("done", value)
        assertTrue(manager.tryClaimFile("FolderA", "file1.txt"))
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

    @Test
    fun testLlmTranslationConfig_EffectiveDictSettings() {
        val config = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(
            dictGeminiModel = "gemini-3.5-flash",
            dictGeminiMergeModel = "gemini-3.5-flash",
            dictOpenRouterModel = "google/gemma-4-31b-it:free",
            dictOpenRouterMergeModel = "meta-llama/llama-3.3-70b-instruct:free",
            dictOpenRouterProviderOrder = listOf("upstage", "baidu/fp8"),
            dictOpenRouterProviderAllowFallbacks = true,
            dictGroqModel = "llama-3.3-70b-versatile",
            dictGroqMergeModel = ""
        )

        // Gemini
        assertEquals("gemini-3.5-flash", config.getEffectiveDictModel(com.example.novelscraper.translation.llm.engine.LlmProvider.GEMINI))
        assertEquals("gemini-3.5-flash", config.getEffectiveDictMergeModel(com.example.novelscraper.translation.llm.engine.LlmProvider.GEMINI))

        // OpenRouter
        assertEquals("google/gemma-4-31b-it:free", config.getEffectiveDictModel(com.example.novelscraper.translation.llm.engine.LlmProvider.OPENROUTER))
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", config.getEffectiveDictMergeModel(com.example.novelscraper.translation.llm.engine.LlmProvider.OPENROUTER))

        // Groq (空欄時は抽出モデルと同じ)
        assertEquals("llama-3.3-70b-versatile", config.getEffectiveDictModel(com.example.novelscraper.translation.llm.engine.LlmProvider.GROQ))
        assertEquals("llama-3.3-70b-versatile", config.getEffectiveDictMergeModel(com.example.novelscraper.translation.llm.engine.LlmProvider.GROQ))

        // OpenRouter ルーティング
        val openRouterConfig = config.copy(dictProvider = com.example.novelscraper.translation.llm.engine.LlmProvider.OPENROUTER)
        assertEquals(listOf("upstage", "baidu/fp8"), openRouterConfig.getEffectiveDictProviderOrder())
        assertEquals(true, openRouterConfig.getEffectiveDictProviderAllowFallbacks())

        // Gemini時のルーティングは空
        val geminiConfig = config.copy(dictProvider = com.example.novelscraper.translation.llm.engine.LlmProvider.GEMINI)
        assertTrue(geminiConfig.getEffectiveDictProviderOrder().isEmpty())
    }

    @Test
    fun testProviderOrderParsing_FullwidthCommas() {
        val input = "upstage、 baidu/fp8，nebius  deepinfra"
        val parsed = input.split(Regex("[,、，\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
        assertEquals(listOf("upstage", "baidu/fp8", "nebius", "deepinfra"), parsed)
    }

    @Test
    fun testLlmTranslationConfig_DictWorkersAndConcurrency() {
        val defaultConfig = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig()
        // デフォルトは gemini-3.1-flash-lite
        assertEquals("gemini-3.1-flash-lite", defaultConfig.getEffectiveDictModel(com.example.novelscraper.translation.llm.engine.LlmProvider.GEMINI))
        // デフォルトは 6 ワーカー × 5 並列 = 30
        assertEquals(6, defaultConfig.dictWorkerCount)
        assertEquals(5, defaultConfig.dictConcurrencyPerWorker)
        assertEquals(30, defaultConfig.getEffectiveDictParallelCount())

        // カスタム設定: 2 ワーカー × 3 並列 = 6
        val customConfig = defaultConfig.copy(dictWorkerCount = 2, dictConcurrencyPerWorker = 3)
        assertEquals(6, customConfig.getEffectiveDictParallelCount())

        // 上限30ガード
        val cappedConfig = defaultConfig.copy(dictWorkerCount = 10, dictConcurrencyPerWorker = 10)
        assertEquals(30, cappedConfig.getEffectiveDictParallelCount())
    }

    @Test
    fun testOpenAiRequest_OmitsNullTemperature() {
        // temperature=null は JSON から省略される (推論モデル400防止)
        val omitted = com.example.novelscraper.translation.llm.api.LlmApiClient.json.encodeToString(
            com.example.novelscraper.translation.llm.api.model.OpenAiChatRequest.serializer(),
            com.example.novelscraper.translation.llm.api.model.OpenAiChatRequest(
                model = "o1",
                messages = listOf(
                    com.example.novelscraper.translation.llm.api.model.OpenAiMessage(role = "system", content = "p")
                ),
                temperature = null
            )
        )
        assertFalse(omitted.contains("temperature"))

        val present = com.example.novelscraper.translation.llm.api.LlmApiClient.json.encodeToString(
            com.example.novelscraper.translation.llm.api.model.OpenAiChatRequest.serializer(),
            com.example.novelscraper.translation.llm.api.model.OpenAiChatRequest(
                model = "x",
                messages = listOf(
                    com.example.novelscraper.translation.llm.api.model.OpenAiMessage(role = "system", content = "p")
                ),
                temperature = 0.7
            )
        )
        assertTrue(present.contains("temperature"))
    }

    @Test
    fun testOpenAiReasoningPayload_QwenIsolation() {
        // qwen系はトップレベル reasoning_effort=none、reasoning JSONなし
        val (qwenReasoning, qwenEffort) =
            com.example.novelscraper.translation.llm.api.OpenAiCompatibleClient.reasoningPayload("qwen/qwen-72b", "high", null)
        assertNull(qwenReasoning)
        assertEquals("none", qwenEffort)

        // 通常モデルは effort JSON を組み立てる
        val (reasoning, effort) =
            com.example.novelscraper.translation.llm.api.OpenAiCompatibleClient.reasoningPayload("deepseek-v4", "high", null)
        assertNotNull(reasoning)
        assertEquals("high", effort)
    }

    @Test
    fun testLlmTranslationConfig_DictBatchMaxBytesLargeCapacity() {
        val config500kb = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig(
            dictBatchMaxBytes = 500000
        )
        assertEquals(500000, config500kb.dictBatchMaxBytes)
    }

    @Test
    fun testGeminiGenerationConfig_MaxOutputTokensSerialization() {
        val cfg = com.example.novelscraper.translation.llm.api.model.GeminiGenerationConfig(
            maxOutputTokens = 65536
        )
        val json = com.example.novelscraper.translation.llm.api.LlmApiClient.json.encodeToString(
            com.example.novelscraper.translation.llm.api.model.GeminiGenerationConfig.serializer(),
            cfg
        )
        assertTrue(json.contains("\"maxOutputTokens\":65536"))
    }

    @Test
    fun testModelProfile_DefaultMaxOutputChars15000() {
        val profile = com.example.novelscraper.translation.llm.engine.ModelProfile(modelName = "gemini-3.5-flash")
        assertEquals(15000, profile.maxOutputChars)

        val config = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig()
        // 中国語: 15000 * 3.0 / 1.6 = 28125B ≒ 28KB
        val zhThreshold = config.getEffectiveSplitThreshold(SourceLanguage.ZH, profile)
        assertEquals(28125, zhThreshold)

        // 韓国語: 15000 * 3.0 / 1.1 = 40909B ≒ 40KB
        val koThreshold = config.getEffectiveSplitThreshold(SourceLanguage.KO, profile)
        assertEquals(40909, koThreshold)

        // ログ出力用フォーマット検証 (15000 -> 1.5万字, 20000 -> 2万字)
        val zhManChars = String.format(java.util.Locale.US, "%.1f", profile.maxOutputChars / 10000.0).removeSuffix(".0")
        assertEquals("1.5", zhManChars)
        val profile20k = profile.copy(maxOutputChars = 20000)
        val manChars20k = String.format(java.util.Locale.US, "%.1f", profile20k.maxOutputChars / 10000.0).removeSuffix(".0")
        assertEquals("2", manChars20k)
    }

    @Test
    fun testGeminiResponse_ThinkingPartFilteringAndMultiPartJoining() {
        val jsonWithThoughts = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "This is internal thinking process...",
                        "thought": true
                      },
                      {
                        "text": "第1パートの翻訳本文です。\n"
                      },
                      {
                        "text": "第2パートの翻訳本文です。[SRC_END]"
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 100,
                "candidatesTokenCount": 50,
                "totalTokenCount": 150,
                "thoughtsTokenCount": 30
              }
            }
        """.trimIndent()

        val resp = com.example.novelscraper.translation.llm.api.LlmApiClient.json.decodeFromString(
            com.example.novelscraper.translation.llm.api.model.GeminiResponse.serializer(),
            jsonWithThoughts
        )
        val candidate = resp.candidates?.firstOrNull()
        val nonThoughtParts = candidate?.content?.parts?.filter { it.thought != true } ?: emptyList()
        val text = nonThoughtParts.mapNotNull { it.text }.joinToString("")

        assertEquals("第1パートの翻訳本文です。\n第2パートの翻訳本文です。[SRC_END]", text)
        assertFalse(text.contains("internal thinking process"))
        assertEquals(30, resp.usageMetadata?.thoughtsTokenCount)
    }

    @Test
    fun testGeminiResponse_SafetyBlockDetection() {
        val jsonSafetyBlock = """
            {
              "candidates": [
                {
                  "finishReason": "SAFETY"
                }
              ]
            }
        """.trimIndent()

        val resp = com.example.novelscraper.translation.llm.api.LlmApiClient.json.decodeFromString(
            com.example.novelscraper.translation.llm.api.model.GeminiResponse.serializer(),
            jsonSafetyBlock
        )
        val candidate = resp.candidates?.firstOrNull()
        assertEquals("SAFETY", candidate?.finishReason)
    }

    @Test
    fun testLlmTranslationConfig_DefaultDictValues() {
        val defaultConfig = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig()
        assertEquals("gemini-3.1-flash-lite", defaultConfig.dictModel)
        assertEquals("gemini-3.1-flash-lite", defaultConfig.dictMergeModel)
        assertEquals("gemini-3.1-flash-lite", defaultConfig.dictGeminiModel)
        assertEquals("gemini-3.1-flash-lite", defaultConfig.dictGeminiMergeModel)
        assertEquals(100000, defaultConfig.dictBatchMaxBytes)
        assertEquals(com.example.novelscraper.translation.llm.engine.DictSampleMode.UNIFORM, defaultConfig.dictSampleMode)
        assertEquals(10000000, defaultConfig.dictMaxTotalScanBytes)
    }

    @Test
    fun testDictKeyCooldownTracker_StateTransitions() {
        val tracker = DictKeyCooldownTracker(listOf("key-A", "key-B", "key-C"), cooldownSec = 60)
        assertEquals(3, tracker.totalKeys)

        // 1. 初期状態: 優先0で key-A が取得できる
        val first = tracker.getAvailableKey(0)
        assertNotNull(first)
        assertEquals(0, first!!.first)
        assertEquals("key-A", first.second)

        // 2. key-A をクールダウン登録
        tracker.markCooldown(0)
        assertTrue(tracker.isCoolingDown(0))
        assertFalse(tracker.isCoolingDown(1))

        // 3. 次回優先0で要求しても、自動で生存キー (key-B) へスキップ
        val next = tracker.getAvailableKey(0)
        assertNotNull(next)
        assertEquals(1, next!!.first)
        assertEquals("key-B", next.second)

        // 4. 残りもすべてクールダウン登録
        tracker.markCooldown(1)
        tracker.markCooldown(2)
        assertTrue(tracker.isCoolingDown(1))
        assertTrue(tracker.isCoolingDown(2))

        // 5. 全キー制限時は null を返し、最小待機時間が正の値を返す
        assertNull(tracker.getAvailableKey(0))
        val waitMs = tracker.getMinCooldownRemainingMillis()
        assertTrue("waitMs should be between 1000 and 60000, but was $waitMs", waitMs in 1000L..60000L)
    }

    @Test
    fun testSelectSampleFiles_Uniform_NoDuplicates_EdgeCases() {
        val tempDir = java.nio.file.Files.createTempDirectory("uniform_edge_test").toFile()
        try {
            // Case 1: 80ファイル (総数 <= 目標100パート)
            val files80 = (1..80).map { i ->
                val f = File(tempDir, "ch_${String.format("%04d", i)}.txt")
                f.createNewFile()
                DocumentFile.fromFile(f)
            }
            val sampled80 = NovelDictionaryGenerator.selectSampleFiles(
                files80,
                totalParts = 100,
                sampleMode = com.example.novelscraper.translation.llm.engine.DictSampleMode.UNIFORM
            )
            assertEquals(80, sampled80.size)
            assertEquals("80話で重複があってはならない", 80, sampled80.distinctBy { it.name }.size)

            // Case 2: 110ファイル (総数 > 目標100パート、前半50と中盤25が重なる境界ケース)
            val files110 = (1..110).map { i ->
                val f = File(tempDir, "ch_${String.format("%04d", i)}.txt")
                if (!f.exists()) f.createNewFile()
                DocumentFile.fromFile(f)
            }
            val sampled110 = NovelDictionaryGenerator.selectSampleFiles(
                files110,
                totalParts = 100,
                sampleMode = com.example.novelscraper.translation.llm.engine.DictSampleMode.UNIFORM
            )
            assertEquals(100, sampled110.size)
            assertEquals("110話で重複があってはならない", 100, sampled110.distinctBy { it.name }.size)

            // Case 3: 300ファイル (総数 >> 目標100パート、長編ケース)
            val files300 = (1..300).map { i ->
                val f = File(tempDir, "ch_${String.format("%04d", i)}.txt")
                if (!f.exists()) f.createNewFile()
                DocumentFile.fromFile(f)
            }
            val sampled300 = NovelDictionaryGenerator.selectSampleFiles(
                files300,
                totalParts = 100,
                sampleMode = com.example.novelscraper.translation.llm.engine.DictSampleMode.UNIFORM
            )
            assertEquals(100, sampled300.size)
            assertEquals("300話で重複があってはならない", 100, sampled300.distinctBy { it.name }.size)
            // 先頭・中盤・終盤が含まれていること
            assertTrue(sampled300.first().name!!.contains("0001"))
            assertTrue(sampled300.last().name!!.contains("0300"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDictionaryResume_CorruptedJsonValidation() {
        // 正常なJSON: 人名あり -> パース成功
        val validJson = """{"style":"カタカナ","characters":{"ルーク":"ルーク"},"genders":{"ルーク":"男"}}"""
        val validParsed = NovelDictionaryGenerator.parseDictionaryJson(validJson)
        assertNotNull(validParsed)
        assertTrue(validParsed!!.characters.isNotEmpty())

        // 破損JSON: 途中で切れた文字列 -> パース失敗 (null)
        val brokenJson = """{"style":"カタカナ","characters":{"ルーク":"""
        val brokenParsed = NovelDictionaryGenerator.parseDictionaryJson(brokenJson)
        assertNull(brokenParsed)

        // 空またはエラー文字列 -> パース失敗 (null)
        val htmlError = """<html><body>502 Bad Gateway</body></html>"""
        val htmlParsed = NovelDictionaryGenerator.parseDictionaryJson(htmlError)
        assertNull(htmlParsed)

        // 人名0件の空辞書 -> characters.isEmpty()
        val emptyDictJson = """{"style":"カタカナ","characters":{},"genders":{}}"""
        val emptyParsed = NovelDictionaryGenerator.parseDictionaryJson(emptyDictJson)
        assertNotNull(emptyParsed)
        assertTrue(emptyParsed!!.characters.isEmpty())
    }
}