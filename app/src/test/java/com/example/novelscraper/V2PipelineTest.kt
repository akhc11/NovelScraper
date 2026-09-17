package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.Attempt
import com.example.novelscraper.translation.v2.pipeline.AttemptOptions
import com.example.novelscraper.translation.v2.pipeline.BatchOutcome
import com.example.novelscraper.translation.v2.pipeline.CallSettled
import com.example.novelscraper.translation.v2.pipeline.ChunkEntry
import com.example.novelscraper.translation.v2.pipeline.ChunkManifest
import com.example.novelscraper.translation.v2.pipeline.COMPLETION_MARKER
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.DriverOutcome
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.SingleResult
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.TranslateContext
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.pipeline.appendMarker
import com.example.novelscraper.translation.v2.pipeline.attemptDrivers
import com.example.novelscraper.translation.v2.pipeline.buildBatchFormat
import com.example.novelscraper.translation.v2.pipeline.buildBatchInput
import com.example.novelscraper.translation.v2.pipeline.escapeBatchInput
import com.example.novelscraper.translation.v2.pipeline.buildSystemPrompt
import com.example.novelscraper.translation.v2.pipeline.checkAndStripMarker
import com.example.novelscraper.translation.v2.pipeline.detectLanguage
import com.example.novelscraper.translation.v2.pipeline.generateDictionary
import com.example.novelscraper.translation.v2.pipeline.buildBatchJsonSchema
import com.example.novelscraper.translation.v2.pipeline.detectBatchSwap
import com.example.novelscraper.translation.v2.pipeline.joinOutputsStreaming
import com.example.novelscraper.translation.v2.pipeline.kanaRate
import com.example.novelscraper.translation.v2.pipeline.meetsKanaFloor
import com.example.novelscraper.translation.v2.pipeline.matchDictionaryMap
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.pipeline.mergeDecision
import com.example.novelscraper.translation.v2.pipeline.parseBatchResponse
import com.example.novelscraper.translation.v2.pipeline.profileMemoTerms
import com.example.novelscraper.translation.v2.pipeline.buildProfileMemoBlock
import com.example.novelscraper.translation.v2.pipeline.collectProfileHints
import com.example.novelscraper.translation.v2.pipeline.selectHintsForTranslate
import com.example.novelscraper.translation.v2.pipeline.transferShortHints
import com.example.novelscraper.translation.v2.pipeline.parseBatchJsonResponse
import com.example.novelscraper.translation.v2.pipeline.parseExtractedNames
import com.example.novelscraper.translation.v2.pipeline.parseNovelDict
import com.example.novelscraper.translation.v2.pipeline.parseNovelDictLenient
import com.example.novelscraper.translation.v2.pipeline.sanitizeNovelDict
import com.example.novelscraper.translation.v2.pipeline.selectSampleFiles
import com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation
import com.example.novelscraper.translation.v2.pipeline.sha256Hex
import com.example.novelscraper.translation.v2.pipeline.sizeRatioOk
import com.example.novelscraper.translation.v2.pipeline.splitIntoChunks
import com.example.novelscraper.translation.v2.pipeline.stripFences
import com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations
import com.example.novelscraper.translation.v2.pipeline.translateBatch
import com.example.novelscraper.translation.v2.pipeline.writeFailed
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.translateSingle
import com.example.novelscraper.translation.v2.pipeline.RefineConfig
import com.example.novelscraper.translation.v2.pipeline.ResidualOptions
import com.example.novelscraper.translation.v2.pipeline.RetryBudget
import com.example.novelscraper.translation.v2.pipeline.callWithRetry
import com.example.novelscraper.translation.v2.pipeline.saveOutputText
import com.example.novelscraper.translation.v2.pipeline.shouldPersistFailed
import com.example.novelscraper.translation.v2.pipeline.writeChunkManifest
import com.example.novelscraper.translation.v2.pipeline.writeChunks
import com.example.novelscraper.translation.v2.pipeline.verifyRejectReason
import com.example.novelscraper.translation.v2.pipeline.verifyTranslation
import com.example.novelscraper.translation.v2.pipeline.LargeOptions
import com.example.novelscraper.translation.v2.pipeline.LargeOutcome
import org.junit.Assert.*
import org.junit.Test

class V2PipelineTest {

    private fun ok(text: String) = LlmResult.Success(text = text)
    private fun fail(kind: FailureKind) =
        LlmResult.Failure(ClassifiedFailure(kind))

    private fun looseCtx(        call: suspend (String, String, String) -> LlmResult,
        drivers: List<String> = listOf("d1"),
        dictionary: com.example.novelscraper.translation.v2.pipeline.NovelDict? = null
    ) = TranslateContext(
        basePrompts = mapOf(1 to "base"),
        promptOrder = listOf(1),
        driverNames = drivers,
        dictionary = dictionary,
        verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
        call = call
    )

    @Test
    fun testLanguage_Detect() {
        assertEquals(SourceLang.ZH, detectLanguage("这是一个关于勇者的故事。他为了拯救王国踏上了旅程。").language)
        assertEquals(SourceLang.KO, detectLanguage("이것은 용사에 관한 이야기입니다. 그는 왕국을 구하기 위해 떠났다.").language)
        assertEquals(SourceLang.EN, detectLanguage("This is a story about a hero who left to save the kingdom.").language)
        assertEquals(SourceLang.JA, detectLanguage("これは勇者に関する物語です。彼は王国を救うために旅に出ました。").language)

        // エッジケース1: 台湾繁体字（簡体字がなくてもZHと判定される）
        assertEquals(SourceLang.ZH, detectLanguage("這是一個關於勇者的故事。他為了拯救王國踏上了旅程。").language)

        // エッジケース2: 中国語＋日本の顔文字（カタカナノイズでJAに誤爆しない）
        assertEquals(SourceLang.ZH, detectLanguage("这是一个关于勇者的故事(´・ω・｀)。他为了拯救王国踏上了旅程。").language)

        // エッジケース3: 韓国語＋英語タイトル（英語タイトル混ざりでENに誤爆しない）
        assertEquals(SourceLang.KO, detectLanguage("[PROLOGUE] 이것은 용사에 관한 이야기입니다. 그는 왕국을 구하기 위해 떠났다.").language)

        // エッジケース4: ゲーム小説の英語ステータス画面混ざり（ステータス画面でENに誤爆しない）
        assertEquals(SourceLang.ZH, detectLanguage("STATUS: HP 100/100, MP 50/50, LEVEL 1\n这是一个关于勇者的故事。他为了拯救王国踏上了旅程。").language)

        // エッジケース5: 日本語の漢字多め・国字（峠、畑）混ざり
        assertEquals(SourceLang.JA, detectLanguage("第一章 峠の決戦。彼らは畑を抜けて山へ向かった。").language)
    }

    @Test
    fun testCalculateInputLimitBytes() {
        val maxChars = 15000
        val zhBytes = V2Settings.calculateInputLimitBytes(SourceLang.ZH, maxChars)
        val koBytes = V2Settings.calculateInputLimitBytes(SourceLang.KO, maxChars)
        val enBytes = V2Settings.calculateInputLimitBytes(SourceLang.EN, maxChars)
        val jaBytes = V2Settings.calculateInputLimitBytes(SourceLang.JA, maxChars)

        assertEquals(28125, zhBytes)
        assertEquals(40909, koBytes)
        assertEquals(26785, enBytes)
        assertEquals(45000, jaBytes)

        val (zhKb, koKb, enKb) = V2Settings.inputSizeEstimateKb(maxChars)
        assertEquals(27, zhKb)
        assertEquals(39, koKb)
        assertEquals(26, enKb)
    }

    @Test
    fun testMarker_AppendCheckStrip() {
        assertEquals("abc", appendMarker("abc", false))
        val marked = appendMarker("abc", true)
        assertTrue(marked.endsWith(COMPLETION_MARKER))
        assertEquals("abc", checkAndStripMarker(marked, true))
        assertNull(checkAndStripMarker("abc", true))
        assertEquals("abc", checkAndStripMarker("abc", false))

        // 表記揺れ・後口上救済（アイデアA）の検証
        assertEquals("本文です。", checkAndStripMarker("本文です。\n[src_end]", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n［SRC_END］", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n[SRC_END]\n以上が翻訳結果です。お役に立てれば幸いです！", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n［src_end］\nEnjoy reading!", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n**[SRC_END]**", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n**[SRC_END]**\n注釈：スラングの解説", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n`[SRC_END]`", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n(SRC_END)", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n（SRC_END）", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\n[SRC-END]", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。\nSRC_END", true))
        assertEquals("本文です。", checkAndStripMarker("本文です。[SRC_END]" + "あ".repeat(350), true))
    }

    @Test
    fun testQuality_SizeAndKana() {
        assertTrue(sizeRatioOk("あああ", "ああああ", 50, 300))
        assertFalse(sizeRatioOk("あ".repeat(100), "あ", 50, 300))
        assertTrue(kanaRate("これは勇者です") > 0.5)
        assertTrue(meetsKanaFloor("これは勇者です"))
        assertFalse(meetsKanaFloor("李云张三"))
    }

    @Test
    fun testChunking_SplitJoinRoundtrip() = kotlinx.coroutines.runBlocking {
        val text = (1..20).joinToString("\n\n") { "段落$it の本文です。" }
        val chunks = splitIntoChunks(text, 100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 400 })

        val store = InMemoryFileStore()
        val root = store.createRoot("w")
        val out = store.createDir(root.uri, "out")!!
        for ((i, c) in chunks.withIndex()) {
            val name = "chunk_" + String.format("%04d", i + 1)
            val doc = store.createFile(out.uri, name, "text/plain")!!
            store.writeText(doc.uri, "訳$i")
        }
        val final = store.createFile(root.uri, "final.txt", "text/plain")!!
        assertTrue(joinOutputsStreaming(store, out.uri, chunks.indices.map { "chunk_" + String.format("%04d", it + 1) }, final.uri))
        val joined = store.readText(final.uri)!!
        assertTrue(joined.startsWith("訳0"))
        assertTrue(joined.contains("訳${chunks.size - 1}"))
    }

    @Test
    fun testChunking_MergeTinyTail() {
        // 1. 末尾がたった1行（極小）の場合: 直前チャンクにスマート吸収されて分割数が増えないこと
        val longParagraph = "これは非常に長い文章の段落です。".repeat(200) // 約6,400バイト
        val tinyTail = "\n\n最後の1行です。" // 約25バイト
        val textWithTinyTail = longParagraph + tinyTail

        // limitBytes = 6000: longParagraph (6400B) がまず分割されるか、または末尾の微小余りが直前に吸収される
        val chunks = splitIntoChunks(textWithTinyTail, 6000)
        val combinedText = chunks.joinToString("")
        assertEquals(textWithTinyTail, combinedText) // 全文の欠損が一切ないこと
        // 末尾チャンクが極小のまま孤立していないことを検証
        if (chunks.size >= 2) {
            val lastChunkBytes = chunks.last().toByteArray(Charsets.UTF_8).size
            val threshold = (6000 * 0.15).toInt().coerceAtLeast(1000)
            assertTrue("Last chunk should be at least threshold or absorbed: $lastChunkBytes", lastChunkBytes >= threshold)
        }

        // 2. 末尾が十分大きい場合（上限の15%以上）: 正常に独立チャンクとして維持されること
        val partA = "段落Aの本文です。\n\n".repeat(100) // 約3,000バイト
        val partB = "段落Bの本文です。\n\n".repeat(100) // 約3,000バイト
        val chunksTwo = splitIntoChunks(partA + partB, 3200)
        assertEquals(2, chunksTwo.size)
        assertEquals(partA + partB, chunksTwo.joinToString(""))
    }

    @Test
    fun testBatchIO_Roundtrip() {
        val input = buildBatchInput(listOf("a.txt" to "你好", "b.txt" to "早上好"))
        assertTrue(input.contains("<doc id=\"1\">"))
        val resp = "<translations>\n<trans id=\"1\">\nこんにちは\n</trans>\n<trans id=\"2\">\nおはよう\n</trans>\n</translations>"
        val parsed = parseBatchResponse(resp)!!
        assertEquals("こんにちは", parsed[1])
        assertEquals("おはよう", parsed[2])
        assertNull(parseBatchResponse("no tags here"))
        // 閉じタグが1つもない途絶はnull
        assertNull(parseBatchResponse("<translations><trans id=\"1\">x"))
        // 外側タグが途切れても閉じた<trans>は部分救出
        assertEquals("x", parseBatchResponse("<translations><trans id=\"1\">x</trans>")?.get(1))
    }

    @Test
    fun testBatchIO_TolerantAndEscaping() {
        // 1. エスケープ: 構造タグは全角化、不等号は温存
        val raw = "彼は<trans id=\"9\">の札を見つけた。x < 100 かつ y > 50。"
        val escaped = escapeBatchInput(raw)
        assertTrue(escaped.contains("＜trans id=\"9\">"))
        assertTrue(escaped.contains("x < 100"))

        // 2. 寛容パース: 挨拶文混在、Markdownブロック、全角タグ、クォートなし、シングルクォート
        val dirtyResp = """
            承知いたしました。以下が翻訳結果です：
            ```xml
            <translations>
            ＜trans id＝＂1＂＞
            こんにちは世界
            ＜/trans＞
            <trans id=2>
            おはようございます
            </trans>
            <trans id='3'>
            おやすみなさい
            </trans>
            </translations>
            ```
            以上です。
        """.trimIndent()
        val parsed = parseBatchResponse(dirtyResp)
        assertNotNull(parsed)
        assertEquals("こんにちは世界", parsed!![1])
        assertEquals("おはようございます", parsed[2])
        assertEquals("おやすみなさい", parsed[3])

        // 3. 部分回収: 3件中2件のみ存在する場合、2件を返却
        val partialResp = "<translations><trans id=\"1\">訳1</trans><trans id=\"3\">訳3</trans></translations>"
        val partialParsed = parseBatchResponse(partialResp)!!
        assertEquals(2, partialParsed.size)
        assertEquals("訳1", partialParsed[1])
        assertEquals("訳3", partialParsed[3])
        assertNull(partialParsed[2])
    }

    @Test
    fun testAttemptDrivers_Rules() = kotlinx.coroutines.runBlocking {
        val noDelay = AttemptOptions(maxSameRetries = 1, retryDelayMs = { 0 })
        // 成功で確定
        val okFirst = attemptDrivers(
            listOf(
                Attempt("d1", "", "") { ok("t1") },
                Attempt("d2", "", "") { ok("t2") }
            ),
            noDelay
        )
        assertEquals(DriverOutcome.Ok("t1"), okFirst)

        // 確定失敗は即次へ（同一切替なし）
        var d1calls = 0
        val blocked = attemptDrivers(
            listOf(
                Attempt("d1", "", "") { d1calls++; fail(FailureKind.BLOCKED_DETERMINISTIC) },
                Attempt("d2", "", "") { ok("t2") }
            ),
            noDelay
        )
        assertEquals(DriverOutcome.Ok("t2"), blocked)
        assertEquals(1, d1calls)

        // 一時失敗は同一再送→次へ
        var retries = 0
        val quota = attemptDrivers(
            listOf(
                Attempt("d1", "", "") { retries++; fail(FailureKind.QUOTA_MINUTE) },
                Attempt("d2", "", "") { ok("t2") }
            ),
            noDelay
        )
        assertEquals(DriverOutcome.Ok("t2"), quota)
        assertEquals(2, retries)

        // 設定のみ全滅はconfigOnly
        val cfg = attemptDrivers(
            listOf(Attempt("d1", "", "") { fail(FailureKind.CONFIG) }),
            noDelay
        )
        assertTrue(cfg is DriverOutcome.GiveUp && cfg.configOnly)

        // 停止
        val stopped = attemptDrivers(
            listOf(Attempt("d1", "", "") { ok("t") }),
            AttemptOptions(stopped = { true })
        )
        assertEquals(DriverOutcome.Stopped, stopped)

        // コスト上限で停止
        val meter = CostMeter(maxTokens = 5)
        val costStop = attemptDrivers(
            listOf(Attempt("d1", "", "") { LlmResult.Success("text", promptTokens = 3, completionTokens = 4) }),
            AttemptOptions(meter = meter, retryDelayMs = { 0 })
        )
        assertEquals(DriverOutcome.Stopped, costStop)
    }

    @Test
    fun testKernel_SettlePolicy() {
        // 失敗内容による扱い分けの単一真実。内容依存の確定的失敗だけ .failed 確定する。
        assertTrue(shouldPersistFailed(SingleResult.Failed(FailureKind.BLOCKED_DETERMINISTIC, isDeterministic = true)))
        assertTrue(shouldPersistFailed(SingleResult.Failed(FailureKind.FATAL, "verify-rejected", isDeterministic = false)))
        assertTrue(shouldPersistFailed(SingleResult.Failed(FailureKind.FATAL, "size-ratio", isDeterministic = false)))
        // 回線・制限等の一時的失敗は未完了保留（.failed を作らない）
        assertFalse(shouldPersistFailed(SingleResult.Failed(FailureKind.QUOTA_DAILY, isDeterministic = false)))
        assertFalse(shouldPersistFailed(SingleResult.Failed(FailureKind.QUOTA_MINUTE, isDeterministic = false)))
        assertFalse(shouldPersistFailed(SingleResult.Failed(FailureKind.RETRYABLE_AFTER, isDeterministic = false)))
        assertFalse(shouldPersistFailed(SingleResult.Failed(FailureKind.CONFIG, isDeterministic = false)))
        assertFalse(shouldPersistFailed(SingleResult.Failed(FailureKind.FATAL, "boom", isDeterministic = false)))
    }

    @Test
    fun testKernel_RetryBudgetScope() = kotlinx.coroutines.runBlocking {
        // 予算の有効範囲だけが呼出側で決まり、回数の数え方は骨格が一元管理する。
        // 運転者単位：同一運転者で予算共有（旧 attemptDrivers と同一回数）
        var d1calls = 0
        val terminal = callWithRetry(
            { d1calls++; fail(FailureKind.QUOTA_MINUTE) },
            RetryBudget(), maxSameRetries = 1, retryDelayMs = { 0 },
            stopped = { false }, meter = null, log = {}
        )
        assertTrue(terminal is CallSettled.Terminal)
        assertEquals(2, d1calls)
        // 確定的失敗は即確定（再送なし）
        var blocked = 0
        callWithRetry(
            { blocked++; fail(FailureKind.BLOCKED_DETERMINISTIC) },
            RetryBudget(), maxSameRetries = 2, retryDelayMs = { 0 },
            stopped = { false }, meter = null, log = {}
        )
        assertEquals(1, blocked)
        // 停止は即停止
        val stopped = callWithRetry(
            { ok("t") }, RetryBudget(), maxSameRetries = 2, retryDelayMs = { 0 },
            stopped = { true }, meter = null, log = {}
        )
        assertEquals(CallSettled.Stopped, stopped)
    }

    @Test
    fun testTranslateSingle_Flow() = kotlinx.coroutines.runBlocking {        val ctx = looseCtx(call = { _, _, source -> ok("訳文:$source") })
        val r = translateSingle("原文", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals("訳文:原文", (r as SingleResult.Translated).text)

        val failed = looseCtx(call = { _, _, _ -> fail(FailureKind.FATAL) })
        assertTrue(translateSingle("原文", failed) is SingleResult.Failed)
        val cfg = looseCtx(call = { _, _, _ -> fail(FailureKind.CONFIG) })
        assertTrue(translateSingle("原文", cfg) is SingleResult.ConfigOnly)
    }

    @Test
    fun testTranslateSingle_FailureCarriesReason() = kotlinx.coroutines.runBlocking {
        // 失敗理由はログ表示用に伝播する（.failed の中身は原文のまま）
        val ctx = looseCtx(
            call = { _, _, _ ->
                LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "cutoff:length"))
            }
        )
        val r = translateSingle("原文", ctx)
        assertTrue(r is SingleResult.Failed)
        assertEquals(FailureKind.FATAL, (r as SingleResult.Failed).terminal)
        assertEquals("cutoff:length", r.note)
    }

    @Test
    fun testTranslateSingle_PromptRetryOnQualityFailure() = kotlinx.coroutines.runBlocking {
        // 1回目のプロンプト#3で品質NG（マーカー欠落）、2回目のプロンプト#7で品質合格するケース
        val logs = mutableListOf<String>()
        var attemptCount = 0
        val ctx = TranslateContext(
            basePrompts = mapOf(3 to "standard", 7 to "retry_short"),
            promptOrder = listOf(3, 7),
            driverNames = listOf("d1"),
            verify = VerifyOptions(markerEnabled = true, sizeMinPct = 10, sizeMaxPct = 300, kanaFloor = 0.2),
            call = { promptNum, _, _ ->
                attemptCount++
                if (promptNum == "3") {
                    // 1回目：マーカーなし（品質不合格）
                    ok("これはテストの訳文です。")
                } else {
                    // 2回目（プロンプト7）：マーカーあり（品質合格）
                    ok("これはリトライで成功したテストの訳文です。\n[SRC_END]")
                }
            },
            log = { logs.add(it) }
        )

        val r = translateSingle("テスト原文です。", ctx)
        assertTrue("2回目のプロンプトで成功してTranslatedになるべき", r is SingleResult.Translated)
        assertEquals("これはリトライで成功したテストの訳文です。", (r as SingleResult.Translated).text)
        assertEquals(2, attemptCount)
        assertTrue(logs.any { it.contains("品質チェック不合格") && it.contains("プロンプト#3") })
        assertTrue(logs.any { it.contains("リトライに成功しました") && it.contains("プロンプト#7") })
    }

    @Test
    fun testTranslateSingle_AllPromptsQualityFailed() = kotlinx.coroutines.runBlocking {
        // 全プロンプトで品質NGだった場合、確定失敗（isDeterministic = true）になるケース
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "p1"),
            promptOrder = listOf(1, 1),
            driverNames = listOf("d1"),
            verify = VerifyOptions(markerEnabled = true),
            call = { _, _, _ ->
                // マーカーなし（品質不合格）
                ok("マーカーのない不正な訳文")
            }
        )

        val r = translateSingle("テスト原文", ctx)
        assertTrue(r is SingleResult.Failed)
        val failed = r as SingleResult.Failed
        assertEquals(FailureKind.FATAL, failed.terminal)
        assertEquals("marker-missing", failed.note)
        assertTrue("全プロンプトで品質不合格時は確定失敗(.failed作成対象)になるべき", failed.isDeterministic)
    }

    @Test
    fun testTranslateSingle_TransientFailureBypassesPromptOrder() = kotlinx.coroutines.runBlocking {
        // 通信障害（RETRYABLE_AFTER）発生時、プロンプト順序（例: [1, 7]）の次を無駄打ちせず即座に脱出するケース
        var callCount = 0
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "p1", 7 to "p7"),
            promptOrder = listOf(1, 7),
            driverNames = listOf("d1"),
            maxSameRetries = 0,
            call = { _, _, _ ->
                callCount++
                fail(FailureKind.RETRYABLE_AFTER)
            }
        )

        val r = translateSingle("テスト原文", ctx)
        assertTrue(r is SingleResult.Failed)
        val failed = r as SingleResult.Failed
        assertEquals(FailureKind.RETRYABLE_AFTER, failed.terminal)
        assertFalse("通信障害時は未完了保留(isDeterministic = false)になるべき", failed.isDeterministic)
        assertEquals("通信障害時はプロンプト順序の次を無駄打ちせず1回で即座に脱出すべき", 1, callCount)
    }

    @Test
    fun testVerifyRejectReason() {
        val loose = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false)
        // 合格時は null
        assertNull(verifyRejectReason("原文です。", "訳文です。", loose))
        // マーカー欠落
        assertEquals("marker-missing", verifyRejectReason("原文", "訳文", VerifyOptions(markerEnabled = true)))
        // かな不足
        assertEquals("kana-floor", verifyRejectReason("原文です。", "李云张三", loose.copy(kanaFloor = 0.2)))
        // サイズ比
        assertEquals(
            "size-ratio",
            verifyRejectReason(
                "あ".repeat(100), "あ",
                VerifyOptions(sizeMinPct = 50, sizeMaxPct = 300, kanaFloor = 0.0, markerEnabled = false)
            )
        )
        // 残留ハングル
        val ko = loose.copy(residual = ResidualOptions(SourceLang.KO))
        val residual = verifyRejectReason("원문", "한".repeat(40), ko)
        assertNotNull(residual)
        assertTrue(residual!!.startsWith("residual"))
        // 行数
        val src6 = (1..6).joinToString("\n") { "$it 行目" }
        assertEquals("line-count", verifyRejectReason(src6, "訳のみ", loose))
    }


    @Test
    fun testWriteFailed_NoDuplicateOnStaleRename() = kotlinx.coroutines.runBlocking {
        // 古い一覧＋自動リネーム環境でも "a.txt.failed (1)" を作らず既存へ上書きする
        val fake = StaleRenameStore()
        val root = fake.inner.createRoot("w")
        val seed = fake.inner.createFile(root.uri, "a.txt.failed", "text/plain")!!
        fake.inner.writeText(seed.uri, "old")
        fake.staleFails = 1
        val ok = writeFailed(fake, root.uri, "a.txt", "src") {}
        assertTrue(ok)
        assertEquals(listOf("a.txt.failed"), fake.childNames(root.uri))
        assertEquals("src", fake.inner.readText(seed.uri))
    }

    @Test
    fun testWriteFailed_PersistentStaleLeavesNoDuplicate() = kotlinx.coroutines.runBlocking {
        // 一覧が回復しなくても重複を積まず失敗扱いにする（次回再試行で自己回復）
        val fake = StaleRenameStore()
        val root = fake.inner.createRoot("w")
        val seed = fake.inner.createFile(root.uri, "a.txt.failed", "text/plain")!!
        fake.inner.writeText(seed.uri, "old")
        fake.staleFails = 100
        val ok = writeFailed(fake, root.uri, "a.txt", "src") {}
        assertFalse(ok)
        assertEquals(listOf("a.txt.failed"), fake.childNames(root.uri))
    }

    @Test
    fun testTranslateBatch_Flow() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("out")
        val ctx = looseCtx(call = { _, _, source ->
            if (source.contains("<documents>")) {
                ok("<translations><trans id=\"1\">訳一</trans><trans id=\"2\">訳二</trans></translations>")
            } else {
                ok("単訳:$source")
            }
        })
        val outcome = translateBatch(
            store, root.uri,
            listOf("a.txt" to "一", "b.txt" to "二"),
            ctx
        )
        assertEquals(2, outcome.completed)
        assertEquals("訳一", store.readText(store.findChild(root.uri, "a.txt")!!.uri))
        assertEquals("訳二", store.readText(store.findChild(root.uri, "b.txt")!!.uri))

        // 全滅時は単体フォールバック＋失敗分.failed
        val root2 = store.createRoot("out2")
        val ctx2 = looseCtx(call = { _, _, source ->
            if (source.contains("<documents>")) fail(FailureKind.FATAL) else ok("単訳")
        })
        val outcome2 = translateBatch(store, root2.uri, listOf("c.txt" to "三"), ctx2)
        assertEquals(1, outcome2.completed)
    }

    @Test
    fun testBatchIO_JsonHybrid_And_RegexRescue() {
        // 1. buildBatchJsonSchema
        val schema = buildBatchJsonSchema()
        assertTrue(schema.contains("\"translations\""))
        assertTrue(schema.contains("\"id\""))
        assertTrue(schema.contains("\"text\""))

        // 2. 標準JSONパース (translations配列)
        val jsonStandard = """
            {
              "translations": [
                {"id": 1, "text": "こんにちは、世界！\n「元気ですか？」"},
                {"id": 2, "text": "おはようございます。"}
              ]
            }
        """.trimIndent()
        val parsedStd = parseBatchResponse(jsonStandard)
        assertNotNull(parsedStd)
        assertEquals("こんにちは、世界！\n「元気ですか？」", parsedStd!![1])
        assertEquals("おはようございます。", parsedStd[2])

        // 3. トップレベル配列形式
        val jsonArray = """[{"id": 1, "text": "第1話訳"}, {"id": 2, "text": "第2話訳"}]"""
        val parsedArr = parseBatchResponse(jsonArray)
        assertNotNull(parsedArr)
        assertEquals("第1話訳", parsedArr!![1])
        assertEquals("第2話訳", parsedArr[2])

        // 4. キーマップ形式 {"1": "...", "2": "..."}
        val jsonMap = """{"1": "マップ第1話", "2": "マップ第2話"}"""
        val parsedMap = parseBatchResponse(jsonMap)
        assertNotNull(parsedMap)
        assertEquals("マップ第1話", parsedMap!![1])
        assertEquals("マップ第2話", parsedMap[2])

        // 5. 途絶・構文エラーJSONからの正規表現救済（末尾が切れて閉じ括弧がない）
        val brokenJson = """
            {
              "translations": [
                {"id": 1, "text": "完成した第1話訳文です"},
                {"id": 2, "text": "途中で途切れた第2話
        """.trimIndent()
        val rescued = parseBatchResponse(brokenJson)
        assertNotNull(rescued)
        assertEquals("完成した第1話訳文です", rescued!![1])
    }

    @Test
    fun testBatchIO_SwapDetection() {
        // 1. 章見出しによるスワップ検知
        val itemsChapter = listOf(
            "ch1.txt" to "第1話\nむかしむかしあるところに...",
            "ch2.txt" to "第2話\n次の日、旅に出た..."
        )
        // 正常：id 1 が第1話、id 2 が第2話
        val normalTrans = mapOf(
            1 to "第1話\n昔々あるところに...",
            2 to "第2話\n翌日、旅に出た..."
        )
        assertFalse(detectBatchSwap(itemsChapter, normalTrans))

        // スワップ：id 1 に第2話、id 2 に第1話が入っている
        val swappedTrans = mapOf(
            1 to "第2話\n翌日、旅に出た...",
            2 to "第1話\n昔々あるところに..."
        )
        assertTrue(detectBatchSwap(itemsChapter, swappedTrans))

        // 2. 固有数字セットによるスワップ検知
        val itemsNumbers = listOf(
            "fileA.txt" to "コード12345と998877のアイテムを購入した。",
            "fileB.txt" to "ステータス554433と776611を確認した。"
        )
        // スワップ：Aの訳文にBの数字(554433, 776611)、Bの訳文にAの数字(12345, 998877)
        val swappedNumbers = mapOf(
            1 to "ステータス554433と776611を確認した。",
            2 to "コード12345と998877のアイテムを購入した。"
        )
        assertTrue(detectBatchSwap(itemsNumbers, swappedNumbers))
    }

    @Test
    fun testTranslateBatch_SequentialContextTracking_NoFutureLeak() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("track_test")
        val items = listOf(
            "c1.txt" to "第1話の原文です。\n一行目\n二行目",
            "c2.txt" to "第2話の原文です。\n三行目\n四行目",
            "c3.txt" to "第3話の原文です。\n五行目\n六行目"
        )

        // バッチ応答では第1話と第3話のみ返し、第2話は欠落させる
        val capturedPrompts = mutableListOf<String>()
        val ctx = looseCtx(call = { _, prompt, source ->
            if (source.contains("<documents>")) {
                ok("""
                    <translations>
                    <trans id="1">
                    第1話の訳文です。第1話の末尾行。
                    </trans>
                    <trans id="3">
                    第3話の訳文です。第3話の末尾行。
                    </trans>
                    </translations>
                """.trimIndent())
            } else {
                capturedPrompts.add(prompt)
                ok("第2話の単訳です。")
            }
        })

        val outcome = translateBatch(store, root.uri, items, ctx, prevSourceTail = "バッチ直前原文")
        assertEquals(3, outcome.completed)
        assertEquals("第1話の訳文です。第1話の末尾行。", store.readText(store.findChild(root.uri, "c1.txt")!!.uri))
        assertEquals("第2話の単訳です。", store.readText(store.findChild(root.uri, "c2.txt")!!.uri))
        assertEquals("第3話の訳文です。第3話の末尾行。", store.readText(store.findChild(root.uri, "c3.txt")!!.uri))

        // 第2話の単体フォールバックプロンプトに「第1話の原文末尾（二行目）」が含まれ、「第3話（未来）」や「バッチ直前原文（先祖返り）」が含まれていないことを確認！
        assertEquals(1, capturedPrompts.size)
        val singlePrompt = capturedPrompts[0]
        assertTrue(singlePrompt.contains("二行目"))
        assertFalse(singlePrompt.contains("バッチ直前原文"))
        assertFalse(singlePrompt.contains("六行目"))
        assertFalse(singlePrompt.contains("第3話"))
    }

    @Test
    fun testTranslateBatch_SwapTriggersFallback() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("swap_test")
        val items = listOf(
            "s1.txt" to "第1話\nむかしむかし",
            "s2.txt" to "第2話\nあるところに"
        )

        var batchCalled = false
        val singleCalls = mutableListOf<String>()

        val ctx = looseCtx(call = { _, _, source ->
            if (source.contains("<documents>")) {
                batchCalled = true
                // スワップした応答（id 1 に第2話、id 2 に第1話）
                ok("""
                    <translations>
                    <trans id="1">
                    第2話の訳文です。
                    </trans>
                    <trans id="2">
                    第1話の訳文です。
                    </trans>
                    </translations>
                """.trimIndent())
            } else {
                singleCalls.add(source)
                if (source.contains("むかしむかし")) ok("第1話の正しい単訳") else ok("第2話の正しい単訳")
            }
        })

        val outcome = translateBatch(store, root.uri, items, ctx)
        assertTrue(batchCalled)
        assertEquals(2, outcome.completed)
        // スワップが検知され、単体フォールバックで翻訳・保存されたこと
        assertEquals("第1話の正しい単訳", store.readText(store.findChild(root.uri, "s1.txt")!!.uri))
        assertEquals("第2話の正しい単訳", store.readText(store.findChild(root.uri, "s2.txt")!!.uri))
        assertEquals(2, singleCalls.size)
    }

    @Test
    fun testTranslateLarge_Flow() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w")
        val work = store.createDir(root.uri, ".parts_f")!!
        var n = 0
        val ctx = looseCtx(call = { _, _, source ->
            n++
            ok(source.lines().joinToString("\n") { "訳$n:$it" })
        })
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val okResult = translateLarge(
            store, work.uri, root.uri, "f.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 120)
        )
        assertTrue(okResult is LargeOutcome.Completed)
        val final = store.readText(store.findChild(root.uri, "f.txt")!!.uri)!!
        assertTrue(final.contains("訳1"))
        assertTrue(final.contains("訳$n"))
        // 作業所は掃除される
        assertNull(store.findChild(root.uri, ".parts_f"))
    }

    @Test
    fun testSplitIntoChunks_PreservesNewlinesAndNoOom() {
        val totalLines = 1000
        val sb = java.lang.StringBuilder()
        for (i in 1..totalLines) {
            if (i % 5 == 0) {
                sb.append("\n") // 空行
            } else {
                sb.append("第${i}行のテスト文章です。\n")
            }
        }
        val text = sb.toString()
        val chunks = com.example.novelscraper.translation.v2.pipeline.splitIntoChunks(text, 500)
        assertTrue(chunks.size > 1)
        val rejoined = chunks.joinToString("")
        assertEquals(text, rejoined)
    }

    /** 宣言書v2の組み立て。実作成名と分割文の対応で内容ハッシュを付ける。 */
    private fun chunkManifestFor(content: String, chunkSize: Int, names: List<String>): ChunkManifest {
        val parts = splitIntoChunks(content, chunkSize)
        assertEquals(parts.size, names.size)
        return ChunkManifest(
            2, sha256Hex(content), chunkSize,
            names.zip(parts) { n, p -> ChunkEntry(n, sha256Hex(p)) }
        )
    }

    @Test
    fun testTranslateLarge_HaltOnFailedChunk() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w_fail")
        val work = store.createDir(root.uri, ".parts_f")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        // 新契約の作業所を用意：入塊＋宣言書＋未解決failed
        val inDir = store.createDir(work.uri, "in")!!
        val outDir = store.createDir(work.uri, "out")!!
        val names = writeChunks(store, inDir.uri, content, 120, {})
        assertTrue(names.isNotEmpty())
        assertTrue(writeChunkManifest(store, work.uri, chunkManifestFor(content, 120, names)))
        val failedDoc = store.createFile(outDir.uri, names[0] + ".failed", "text/plain")!!
        store.writeText(failedDoc.uri, "failed reason")

        val ctx = looseCtx(call = { _, _, _ -> ok("訳文") })
        val result = translateLarge(
            store, work.uri, root.uri, "f.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 120)
        )
        assertTrue(result is LargeOutcome.Held)
        assertTrue((result as LargeOutcome.Held).reason.isNotBlank())
        // 未解決failedがある場合は作業所が保持される
        assertNotNull(store.findChild(root.uri, ".parts_f"))
    }

    @Test
    fun testTranslateLarge_DeadAfterConsecutiveHalts() = kotlinx.coroutines.runBlocking {
        // 同一記録での未解決停止が続くと終端（親失敗記録の対象）になること
        val store = InMemoryFileStore()
        val root = store.createRoot("w_dead")
        val work = store.createDir(root.uri, ".parts_f")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val options = com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 120)
        val blocked = looseCtx(call = { _, _, _ ->
            LlmResult.Failure(ClassifiedFailure(FailureKind.BLOCKED_DETERMINISTIC, note = "blocked"))
        })
        // 1回目：確定的失敗で記録が残り、保持で終わる
        val r1 = translateLarge(store, work.uri, root.uri, "f.txt", content, blocked, options)
        assertTrue(r1 is LargeOutcome.Held)
        // 2回目：未解決停止（連続1回目）で保持のまま
        val r2 = translateLarge(store, work.uri, root.uri, "f.txt", content, blocked, options)
        assertTrue(r2 is LargeOutcome.Held)
        // 3回目：連続上限で終端になる
        val r3 = translateLarge(store, work.uri, root.uri, "f.txt", content, blocked, options)
        assertTrue(r3 is LargeOutcome.Dead)
        assertTrue((r3 as LargeOutcome.Dead).reason.isNotBlank())
    }

    @Test
    fun testTranslateLarge_Resume_Flow() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w_resume")
        val work = store.createDir(root.uri, ".parts_f")!!
        val content = "パート1の原文\nパート2の原文\nパート3の原文\n"
        // 新契約の作業所を用意：入塊＋宣言書＋先頭塊の既訳のみ
        val inDir = store.createDir(work.uri, "in")!!
        val outDir = store.createDir(work.uri, "out")!!
        val names = writeChunks(store, inDir.uri, content, 25, {})
        assertTrue(names.size >= 2)
        assertTrue(writeChunkManifest(store, work.uri, chunkManifestFor(content, 25, names)))
        val outDoc1 = store.createFile(outDir.uri, names[0], "text/plain")!!
        store.writeText(outDoc1.uri, "パート1の既訳文\n")

        var callCount = 0
        val ctx = looseCtx(call = { _, _, source ->
            callCount++
            ok("翻訳結果:$source")
        })

        val okResult = translateLarge(
            store, work.uri, root.uri, "f.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 25)
        )
        assertTrue(okResult is LargeOutcome.Completed)
        val finalDoc = store.findChild(root.uri, "f.txt")
        assertNotNull(finalDoc)
        val finalText = store.readText(finalDoc!!.uri)!!
        // 既訳のパート1が含まれ、後続パートも結合されていること
        assertTrue(finalText.contains("パート1の既訳文"))
        assertTrue(finalText.contains("翻訳結果:"))
        // 既訳分は再送しないこと
        assertEquals(names.size - 1, callCount)
        // 作業所が正常にクリーンアップされていること
        assertNull(store.findChild(root.uri, ".parts_f"))
    }

    @Test
    fun testChunkSession_PartialInWithoutManifest_ResplitsFully() = kotlinx.coroutines.runBlocking {
        // F1回帰：宣言書なしの欠けた入塊は「完成」と誤認せず作り直し、訳文の欠落を出さないこと
        val store = InMemoryFileStore()
        val root = store.createRoot("w_partial")
        val work = store.createDir(root.uri, ".parts_f")!!
        val content = "第一部のお話です。\n".repeat(20) + "第二部のお話です。\n".repeat(20)
        // 途中失敗の残骸：先頭塊だけが入塊に残り、宣言書はない
        val inDir = store.createDir(work.uri, "in")!!
        store.createDir(work.uri, "out")!!
        val first = splitIntoChunks(content, 300).first()
        val stale = store.createFile(inDir.uri, "chunk_0001", "text/plain")!!
        store.writeText(stale.uri, first)

        var callCount = 0
        val ctx = looseCtx(call = { _, _, source ->
            callCount++
            ok("訳:$source")
        })
        val okResult = translateLarge(
            store, work.uri, root.uri, "f.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 300)
        )
        assertTrue(okResult is LargeOutcome.Completed)
        val finalText = store.readText(store.findChild(root.uri, "f.txt")!!.uri)!!
        // 全塊が翻訳されていること（欠落結合の防止）
        val expectedChunks = splitIntoChunks(content, 300).size
        assertEquals(expectedChunks, callCount)
        assertTrue(finalText.contains("第二部のお話です。"))
    }

    @Test
    fun testChunkSession_HashMismatch_Resplits() = kotlinx.coroutines.runBlocking {
        // 設定・原文の変更後は古い作業所を使い回さないこと
        val store = InMemoryFileStore()
        val root = store.createRoot("w_mismatch")
        val work = store.createDir(root.uri, ".parts_f")!!
        val oldContent = "古いお話です。\n".repeat(40)
        val newContent = "新しいお話です。\n".repeat(40)
        val inDir = store.createDir(work.uri, "in")!!
        store.createDir(work.uri, "out")!!
        val oldNames = writeChunks(store, inDir.uri, oldContent, 300, {})
        assertTrue(writeChunkManifest(store, work.uri, chunkManifestFor(oldContent, 300, oldNames)))

        val ctx = looseCtx(call = { _, _, source -> ok("訳:$source") })
        val okResult = translateLarge(
            store, work.uri, root.uri, "f.txt", newContent, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 300)
        )
        assertTrue(okResult is LargeOutcome.Completed)
        val finalText = store.readText(store.findChild(root.uri, "f.txt")!!.uri)!!
        assertTrue(finalText.contains("新しいお話です。"))
        assertFalse(finalText.contains("古いお話です。"))
    }

    @Test
    fun testChunkSession_LegacyManifest_TransitionalReuse() = kotlinx.coroutines.runBlocking {
        // 旧形式（名簿のみ）は移行受入れし、作り直さず再利用できること
        val store = InMemoryFileStore()
        val root = store.createRoot("w_legacy")
        val work = store.createDir(root.uri, ".parts_f")!!
        val content = "昔のお話です。\n".repeat(40)
        val inDir = store.createDir(work.uri, "in")!!
        store.createDir(work.uri, "out")!!
        val names = writeChunks(store, inDir.uri, content, 300, {})
        val legacyJson = "{\"version\":1,\"sourceHash\":\"${sha256Hex(content)}\",\"chunkSizeBytes\":300," +
            "\"chunks\":[${names.joinToString(",") { "\"$it\"" }}]}"
        val legacyDoc = store.createFile(work.uri, "manifest.json", "application/json")!!
        store.writeText(legacyDoc.uri, legacyJson)

        var calls = 0
        val ctx = looseCtx(call = { _, _, source -> calls++; ok("訳:$source") })
        val okResult = translateLarge(
            store, work.uri, root.uri, "f.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 300)
        )
        assertTrue(okResult is LargeOutcome.Completed)
        assertEquals(names.size, calls)
        assertTrue(store.readText(store.findChild(root.uri, "f.txt")!!.uri)!!.contains("昔のお話です。"))
    }

    @Test
    fun testSaveOutputText_FallbackWithoutRename() = kotlinx.coroutines.runBlocking {
        // 置換非対応でも直接確定＋照合で保存できること
        val inner = InMemoryFileStore()
        val store = object : FileStore by inner {
            override suspend fun renameFile(dirUri: String, fileUri: String, newName: String): VDoc? = null
        }
        val root = inner.createRoot("w_fallback")
        val saved = saveOutputText(store, root.uri, "a.txt", "本文です。", log = {})
        assertNotNull(saved)
        assertEquals("本文です。", inner.readText(inner.findChild(root.uri, "a.txt")!!.uri))
        // 別名残骸が残らないこと
        assertTrue(inner.children(root.uri).none { it.name.startsWith(".tmp_") })
    }

    /**
     * 実機再現：一覧未反映・表示名改変で別名が名寄せ不可でも、確定済みURI照合で保存できること。
     */
    private class TmpBlindStore(val inner: InMemoryFileStore = InMemoryFileStore()) : FileStore by inner {
        override suspend fun findChild(dirUri: String, name: String): VDoc? {
            if (name.startsWith(".tmp_")) return null
            return inner.findChild(dirUri, name)
        }
    }

    @Test
    fun testSaveOutputText_TmpBlindListing_Succeeds() = kotlinx.coroutines.runBlocking {
        val blind = TmpBlindStore()
        val root = blind.inner.createRoot("w_blind")
        val saved = saveOutputText(blind, root.uri, "a.txt", "本文です。", log = {})
        assertNotNull(saved)
        assertEquals("本文です。", blind.inner.readText(blind.inner.findChild(root.uri, "a.txt")!!.uri))
        assertTrue(blind.inner.children(root.uri).none { it.name.startsWith(".tmp_") })
    }

    @Test
    fun testChunkManifest_TmpBlindListing_Succeeds() = kotlinx.coroutines.runBlocking {
        val blind = TmpBlindStore()
        val root = blind.inner.createRoot("w_blind_manifest")
        val work = blind.inner.createDir(root.uri, ".parts_f")!!
        val content = "お話です。\n".repeat(20)
        val names = writeChunks(blind, blind.inner.createDir(work.uri, "in")!!.uri, content, 300, {})
        assertTrue(names.isNotEmpty())
        assertTrue(writeChunkManifest(blind, work.uri, chunkManifestFor(content, 300, names)))
        assertNotNull(blind.inner.findChild(work.uri, "manifest.json"))
    }

    @Test
    fun testTranslateLarge_TmpBlindListing_Succeeds() = kotlinx.coroutines.runBlocking {
        val blind = TmpBlindStore()
        val root = blind.inner.createRoot("w_blind_large")
        val work = blind.inner.createDir(root.uri, ".parts_f")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val ctx = looseCtx(call = { _, _, source -> ok("訳:$source") })
        val okResult = translateLarge(
            blind, work.uri, root.uri, "f.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 300)
        )
        assertTrue(okResult is LargeOutcome.Completed)
        val finalText = blind.inner.readText(blind.inner.findChild(root.uri, "f.txt")!!.uri)!!
        assertTrue(finalText.contains("行30 の本文です。"))
        assertNull(blind.inner.findChild(root.uri, ".parts_f"))
    }

    @Test
    fun testSplitIntoChunks_SmartAbsorbAndOversized() {
        // 超長行（改行なしの長文）＋サロゲートペア（𠮷野家、絵文字🎉）
        val surrogate = "𠮷野家の牛丼🎉"
        val oversizedLine = surrogate.repeat(50) // 約450バイト
        val chunks = com.example.novelscraper.translation.v2.pipeline.splitIntoChunks(oversizedLine, 150)
        assertTrue(chunks.size > 1)
        // 再結合時にサロゲートペアが壊れていないこと
        val rejoined = chunks.joinToString("")
        assertEquals(oversizedLine, rejoined)
    }

    @Test
    fun testJoinOutputsStreaming_BoundaryNormalizations() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w_join")
        val outDir = store.createDir(root.uri, "out")!!

        // ケース1: 末尾改行落ち（癒着防止で \n が補完されること）
        val doc1 = store.createFile(outDir.uri, "chunk_0001", "text/plain")!!
        store.writeText(doc1.uri, "第1段落本文")
        val doc2 = store.createFile(outDir.uri, "chunk_0002", "text/plain")!!
        store.writeText(doc2.uri, "第2段落本文")

        val finalDoc = store.createFile(root.uri, "final.txt", "text/plain")!!
        assertTrue(joinOutputsStreaming(store, outDir.uri, listOf("chunk_0001", "chunk_0002"), finalDoc.uri))
        assertEquals("第1段落本文\n第2段落本文", store.readText(finalDoc.uri))

        // ケース2: 段落区切り（\n\n が正しく維持されること）
        store.writeText(doc1.uri, "第1段落本文\n\n")
        store.writeText(doc2.uri, "\n\n第2段落本文") // 前後両方に空行があっても増殖せず \n\n に正規化
        assertTrue(joinOutputsStreaming(store, outDir.uri, listOf("chunk_0001", "chunk_0002"), finalDoc.uri))
        assertEquals("第1段落本文\n\n第2段落本文", store.readText(finalDoc.uri))
    }

    @Test
    fun testTranslateLarge_WithDictionaryAndContextInjection() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w_dict")
        val work = store.createDir(root.uri, ".parts_dict")!!

        val dict = com.example.novelscraper.translation.v2.pipeline.NovelDict(
            characters = mapOf("山田" to "ヤマダ", "佐藤" to "サトウ"),
            profiles = mapOf("山田" to "内気で控えめな小動物系少女")
        )

        val capturedPrompts = mutableListOf<String>()
        val ctx = looseCtx(
            dictionary = dict,
            call = { _, prompt, source ->
                capturedPrompts.add(prompt)
                ok("訳文\n$source")
            }
        )

        val content = "山田が歩いていた。\n".repeat(40) + "佐藤が走ってきた。\n".repeat(40)
        val okResult = translateLarge(
            store, work.uri, root.uri, "dict_test.txt", content, ctx,
            com.example.novelscraper.translation.v2.pipeline.LargeOptions(chunkSizeBytes = 1120),
            prevSourceTail = "前話の最後の行です。"
        )
        assertTrue(okResult is LargeOutcome.Completed)
        assertEquals(2, capturedPrompts.size)

        val prompt1 = capturedPrompts[0]
        assertTrue(prompt1.contains("[確定訳語]"))
        assertFalse(prompt1.contains("[人物対応表]"))
        assertTrue(prompt1.contains("=== PREVIOUS TEXT"))
        assertTrue(prompt1.contains("前話の最後の行です。"))
        assertFalse(prompt1.contains("=== PREVIOUS CONTEXT"))

        val prompt2 = capturedPrompts[1]
        assertTrue(prompt2.contains("[確定訳語]"))
        assertFalse(prompt2.contains("[人物対応表]"))
        assertTrue(prompt2.contains("=== PREVIOUS CONTEXT"))
        assertTrue(prompt2.contains("ヤマダ"))
        assertFalse(prompt2.contains("=== PREVIOUS TEXT"))
        assertFalse(prompt2.contains("前話の最後の行です。"))

        val finalDoc = store.findChild(root.uri, "dict_test.txt")
        assertNotNull(finalDoc)
        val finalText = store.readText(finalDoc!!.uri)!!
        assertTrue(finalText.contains("訳文"))
    }

    @Test
    fun testDictStage_PartialAndHold() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("d")
        val goodJson = """{"style":"カタカナ","characters":{"山田":"ヤマダ"},"genders":{}}"""
        val files = listOf("a.txt" to "山田の物語", "b.txt" to "BLOCK対象")
        val byName = files.toMap()
        // bだけ確定的失敗 → 部分マージで確定（2バッチ化のため上限を小さく）
        val dict = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, text ->
                if (text.contains("BLOCK")) fail(FailureKind.BLOCKED_DETERMINISTIC) else ok(goodJson)
            },
            DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNotNull(dict)
        assertEquals("ヤマダ", dict!!.characters["山田"])

        // 一時的失敗のみ → 保留
        val root2 = store.createRoot("d2")
        val held = generateDictionary(
            store, root2.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, _ -> fail(FailureKind.QUOTA_MINUTE) },
            DictOptions(maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNull(held)

        // FATAL（JSON崩れ等）は再送するが全体保留にはしない → 部分マージで確定
        val root3 = store.createRoot("d3")
        val fatalPartial = generateDictionary(
            store, root3.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, text ->
                if (text.contains("BLOCK")) fail(FailureKind.FATAL) else ok(goodJson)
            },
            DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNotNull(fatalPartial)
        assertEquals("ヤマダ", fatalPartial!!.characters["山田"])

        // 想定外例外（RuntimeException）が発生してもスコープ全体が道連れにならず隔離される（supervisorScope検証）
        val root4 = store.createRoot("d4")
        val isolated = generateDictionary(
            store, root4.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, text ->
                if (text.contains("BLOCK")) throw RuntimeException("Simulated unexpected crash in batch")
                else ok(goodJson)
            },
            DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNotNull(isolated)
        assertEquals("ヤマダ", isolated!!.characters["山田"])
    }

    @Test
    fun testDictSanitize() {
        // 日本語見出しでない値は落とす（韓国語のままの採用を防ぐ）。人物メモも連動して刈る。
        val dict = NovelDict(
            style = "カタカナ",
            characters = mapOf("山田" to "ヤマダ", "김민준" to "김민준", "John" to "ジョン", "李云" to "李雲"),
            profiles = mapOf(
                "山田" to "内気で控えめな小動物系少女",
                "김민준" to "용감한 소년",
                "John" to "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろ",
                "李云" to "落ち着いた宗主の少年",
                "幽霊" to "登場しない人物のメモ"
            )
        )
        val cleaned = sanitizeNovelDict(dict)
        assertEquals(mapOf("山田" to "ヤマダ", "John" to "ジョン", "李云" to "李雲"), cleaned.characters)
        // メモは生存項目に連動し、超過・非日本語はその人物分だけ落ちる
        assertEquals(mapOf("山田" to "内気で控えめな小動物系少女", "李云" to "落ち着いた宗主の少年"), cleaned.profiles)
        assertNull(cleaned.characters["김민준"])
        assertNull(cleaned.profiles["김민준"])
        assertNull(cleaned.profiles["John"])
        assertNull(cleaned.profiles["幽霊"])
    }

    @Test
    fun testProfileMemoTermsAndBlock() {
        // 登場語の訳語を見出しに、未登録・空文は落とす。空は空文字。
        val terms = mapOf("李云" to "李雲", "李云龙" to "李雲龍", "野良" to "野良")
        val profiles = mapOf("李云" to "落ち着いた宗主の少年", "李云龙" to "", "幽霊" to "出ない人物")
        assertEquals(mapOf("李雲" to "落ち着いた宗主の少年"), profileMemoTerms(terms, profiles))
        assertTrue(profileMemoTerms(terms, emptyMap()).isEmpty())
        assertEquals("", buildProfileMemoBlock(emptyMap()))
        val block = buildProfileMemoBlock(mapOf("李雲" to "落ち着いた宗主の少年"))
        assertTrue(block.contains("[登場人物メモ]"))
        assertTrue(block.contains("参考情報"))
        assertTrue(block.contains("- 李雲：落ち着いた宗主の少年"))
    }

    @Test
    fun testTranslateSingle_ProfileMemoAttached() = kotlinx.coroutines.runBlocking {
        // 登場人物のメモだけ添付し、未登場は送らない。OFF時は送らない。
        val dict = NovelDict(
            style = "漢字",
            characters = mapOf("李云" to "李雲", "佐藤" to "サトウ"),
            profiles = mapOf("李云" to "落ち着いた宗主の少年", "佐藤" to "内気で控えめな小動物系少女")
        )
        val prompts = mutableListOf<String>()
        val ctx = looseCtx(
            dictionary = dict,
            call = { _, prompt, _ ->
                synchronized(prompts) { prompts.add(prompt) }
                ok("李雲が歩いた。")
            }
        )
        val r = translateSingle("李云が歩いた。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals(1, prompts.size)
        assertTrue(prompts[0].contains("[登場人物メモ]"))
        assertTrue(prompts[0].contains("- 李雲：落ち着いた宗主の少年"))
        assertFalse(prompts[0].contains("サトウ"))
        val offPrompts = mutableListOf<String>()
        val offCtx = looseCtx(dictionary = dict, call = { _, prompt, _ ->
            synchronized(offPrompts) { offPrompts.add(prompt) }
            ok("李雲が歩いた。")
        }).copy(profileMemoEnabled = false)
        assertTrue(translateSingle("李云が歩いた。", offCtx) is SingleResult.Translated)
        assertFalse(offPrompts[0].contains("[登場人物メモ]"))
    }

    @Test
    fun testTranslateBatch_ProfileMemoAttached() = kotlinx.coroutines.runBlocking {
        // 束ね路でも登場分だけメモが付く（単品路と同一ヘルパーのため束ね固有則なし）。
        val store = InMemoryFileStore()
        val root = store.createRoot("batch_memo")
        val items = listOf("c1.txt" to "李云が歩いた。", "c2.txt" to "風が吹いた。")
        val batchPrompts = mutableListOf<String>()
        val ctx = looseCtx(
            dictionary = NovelDict(
                style = "漢字",
                characters = mapOf("李云" to "李雲"),
                profiles = mapOf("李云" to "落ち着いた宗主の少年")
            ),
            call = { _, prompt, source ->
                if (source.contains("<documents>")) {
                    synchronized(batchPrompts) { batchPrompts.add(prompt) }
                    ok("""
                        <translations>
                        <trans id="1">
                        李雲が歩いた。
                        </trans>
                        <trans id="2">
                        風が吹いた。
                        </trans>
                        </translations>
                    """.trimIndent())
                } else {
                    ok(source)
                }
            }
        )
        val outcome = translateBatch(store, root.uri, items, ctx)
        assertEquals(2, outcome.completed)
        assertEquals(1, batchPrompts.size)
        assertTrue(batchPrompts[0].contains("[登場人物メモ]"))
        assertTrue(batchPrompts[0].contains("- 李雲：落ち着いた宗主の少年"))
    }

    @Test
    fun testDictStage_AllNonJapaneseHolds() = kotlinx.coroutines.runBlocking {
        // 全項目が原文表記のままなら黙って採用せず保留にする（大声ログつき）
        val store = InMemoryFileStore()
        val root = store.createRoot("d-ko-echo")
        val echoJson = """{"style":"カタカナ","characters":{"김민준":"김민준"},"genders":{}}"""
        val files = listOf("a.txt" to "민준의 이야기", "b.txt" to "하늘의 노래")
        val byName = files.toMap()
        val logs = mutableListOf<String>()
        val dict = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, _ -> ok(echoJson) },
            DictOptions(maxRetriesPerBatch = 0, parallelism = 2),
            log = { synchronized(logs) { logs.add(it) } }
        )
        assertNull(dict)
        assertTrue(logs.any { it.contains("日本語でない") })
    }

    @Test
    fun testDictExamples_NoFallbackWhenNoMatch() = kotlinx.coroutines.runBlocking {
        // 完全一致ゼロ時は参考例のフォールバック注入を行わず、辞書ブロックを一切注入しない（トークン浪費・ハルシネーション防止）
        val prompts = mutableListOf<String>()
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "base"),
            promptOrder = listOf(1),
            driverNames = listOf("d1"),
            dictionary = NovelDict(style = "カタカナ", characters = mapOf("山田" to "ヤマダ")),
            verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
            call = { _, prompt, _ ->
                synchronized(prompts) { prompts.add(prompt) }
                ok("訳文")
            }
        )
        val r = translateSingle("本文に名前なし", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals(1, prompts.size)
        assertFalse("完全一致ゼロ時は参考例が注入されないこと", prompts[0].contains("参考例"))
        assertFalse("完全一致ゼロ時は登場人物対応表が注入されないこと", prompts[0].contains("登場人物対応表"))
        assertFalse("完全一致ゼロ時は無関係な人物名が注入されないこと", prompts[0].contains("ヤマダ"))
    }

    @Test
    fun testDictParse_NestedFallback() {
        // 入れ子形式 {"名前": {"name": "読み"}} も救済する（旧版の柔軟パーサー復活）。旧genderキーは無視する。
        val nested = """{"style":"カタカナ","characters":{"김민준":{"name":"金","gender":"男"}}}"""
        val parsed = parseNovelDict(nested)
        assertNotNull(parsed)
        assertEquals("金", parsed!!.characters["김민준"])
        assertTrue(parsed.profiles.isEmpty())
    }

    @Test
    fun testDictParse_ProfilesAndLegacyGenders() {
        // 新形式のprofilesは読み、旧形式のgendersキーは無視する（後方互換）。
        val withProfiles = parseNovelDictLenient(
            """{"style":"漢字","characters":{"李云":"李雲"},"profiles":{"李云":"落ち着いた宗主の少年"},"genders":{"李云":"男"}}"""
        )
        assertNotNull(withProfiles)
        assertEquals("落ち着いた宗主の少年", withProfiles!!.profiles["李云"])
        val legacy = parseNovelDictLenient("""{"style":"漢字","characters":{"李云":"李雲"}}""")
        assertNotNull(legacy)
        assertTrue(legacy!!.profiles.isEmpty())
    }

    @Test
    fun testDictStage_EmptyFinalized() = kotlinx.coroutines.runBlocking {
        // 人名ゼロは空のまま完成扱い（毎回の再生成ループにしない）。レビューは素通し。
        val store = InMemoryFileStore()
        val root = store.createRoot("d-empty")
        val emptyJson = """{"style":"カタカナ","characters":{},"genders":{}}"""
        var calls = 0
        val files = listOf("a.txt" to "風の音だけが聞こえる丘の昼下がり", "b.txt" to "雨上がりの空に雲が流れる")
        val byName = files.toMap()
        val dict = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, _ -> calls++; ok(emptyJson) },
            DictOptions(maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNotNull(dict)
        assertTrue(dict!!.characters.isEmpty())
        // 単一バッチのため抽出のみ（マージ・レビュー呼び出しなし）
        assertEquals(1, calls)
    }

    @Test
    fun testDictStage_HashMismatchRegen() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("d")
        val goodJson = """{"style":"カタカナ","characters":{"山田":"ヤマダ"},"genders":{}}"""
        val files = listOf("a.txt" to "山田の物語")
        val byName = files.toMap()
        val batchPrompt = DictOptions().prompts.batch
        var batchCalls = 0
        val countingCall: suspend (String, String, String) -> LlmResult = { _, prompt, _ ->
            if (prompt == batchPrompt) batchCalls++
            ok(goodJson)
        }
        val first = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = countingCall,
            DictOptions(maxRetriesPerBatch = 0)
        )
        assertNotNull(first)
        assertEquals(1, batchCalls)
        // 内容変更なし → 抽出はキャッシュ再利用（レビューは毎回走る）
        val second = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = countingCall,
            DictOptions(maxRetriesPerBatch = 0)
        )
        assertNotNull(second)
        assertEquals(1, batchCalls)
        // 内容変更 → ハッシュ不一致で抽出から再取得
        val revised = listOf("a.txt" to "山田の物語・改訂版")
        val revisedByName = revised.toMap()
        val third = generateDictionary(
            store, root.uri, revised.map { it.first },
            readText = { revisedByName[it] },
            call = countingCall,
            DictOptions(maxRetriesPerBatch = 0)
        )
        assertNotNull(third)
        assertEquals(2, batchCalls)
    }

    @Test
    fun testDictStage_GarbageChainHolds() = kotlinx.coroutines.runBlocking {
        // 解析不能な応答だけの場合は空辞書を確定せず保留し、親失敗記録も作らないこと
        val store = InMemoryFileStore()
        val root = store.createRoot("d-garbage")
        val files = listOf("a.txt" to "山田の物語")
        val byName = files.toMap()
        val dict = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, _ -> ok("this is not json at all") },
            DictOptions(maxRetriesPerBatch = 1, parallelism = 1)
        )
        assertNull(dict)
        assertNull(store.findChild(root.uri, "dictionary.json"))
    }

    @Test
    fun testDictStage_ModelChangeRefetches() = kotlinx.coroutines.runBlocking {
        // モデル変更時は本文一致でも取り直すこと（抽出呼出しのみ数える）
        val store = InMemoryFileStore()
        val root = store.createRoot("d-model")
        val goodJson = """{"style":"カタカナ","characters":{"山田":"ヤマダ"},"genders":{}}"""
        val files = listOf("a.txt" to "山田の物語")
        val byName = files.toMap()
        val batchPrompt = DictOptions().prompts.batch
        var batchCalls = 0
        val countingCall: suspend (String, String, String) -> LlmResult = { _, prompt, _ ->
            if (prompt == batchPrompt) batchCalls++
            ok(goodJson)
        }
        val first = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = countingCall,
            DictOptions(model = "m1", maxRetriesPerBatch = 0, parallelism = 1)
        )
        assertNotNull(first)
        assertEquals(1, batchCalls)
        val second = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = countingCall,
            DictOptions(model = "m2", maxRetriesPerBatch = 0, parallelism = 1)
        )
        assertNotNull(second)
        assertEquals(2, batchCalls)
    }

    @Test
    fun testDictStage_ImmediateRetry() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val goodJson = """{"style":"カタカナ","characters":{"山田":"ヤマダ"}}"""
        val files = listOf("a.txt" to "山田の冒険")
        val byName = files.toMap()

        // 1. バッチ抽出で一時失敗しても、DictStage自身は待機せず即座に再試行（call側へ一本化）
        val root0 = store.createRoot("d0")
        var attempts0 = 0
        val dict0 = generateDictionary(
            store, root0.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, _, _ ->
                if (attempts0++ == 0) fail(FailureKind.FATAL) else ok(goodJson)
            },
            options = DictOptions(maxRetriesPerBatch = 2)
        )
        assertNotNull(dict0)
        assertEquals(3, attempts0) // バッチ抽出（失敗1+成功1）+ レビュー（成功1）

        // 2. 辞書レビューで一時失敗しても、即座に再試行して完了
        val rootReview = store.createRoot("d_review")
        var revCalls = 0
        val dictRev = generateDictionary(
            store, rootReview.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, prompt, _ ->
                if (prompt.contains("Review the merged")) {
                    if (revCalls++ == 0) fail(FailureKind.FATAL) else ok(goodJson)
                } else {
                    ok(goodJson)
                }
            },
            options = DictOptions(maxRetriesPerBatch = 2, reviewRetries = 2)
        )
        assertNotNull(dictRev)
        assertEquals(2, revCalls)
    }

    @Test
    fun testDictHelpers() {
        val picked = selectSampleFiles((1..10).map { "f$it" }, 4, true)
        assertEquals(4, picked.size)
        assertEquals(picked.distinct().size, 4)
        assertEquals(listOf("a"), selectSampleFiles(listOf("a"), 5, true))
        assertNotNull(parseNovelDict("```json\n{\"style\":\"漢字\",\"characters\":{\"李云\":\"李雲\"},\"genders\":{}}\n```"))
        assertNull(parseNovelDict("not json"))
        assertNull(parseNovelDict("{\"style\":\"x\",\"characters\":{}}"))
        assertEquals(64, sha256Hex("abc").length)
        assertTrue(mergeDecision(3, 2, false))
        assertFalse(mergeDecision(3, 2, true))
        assertFalse(mergeDecision(3, 0, false))
        assertFalse(mergeDecision(3, 3, false))
        val matched = matchDictionaryMap("山田と田中", mapOf("山田" to "ヤマダ", "佐藤" to "サトウ"))
        assertEquals(mapOf("山田" to "ヤマダ"), matched)
        val ann2 = selectTermAnnotation("山田と田中")!!
        val prompt = buildSystemPrompt("base", previousTranslatedTail = "prev", termAnnotation = ann2)
        assertTrue(prompt.contains("PREVIOUS CONTEXT"))
        assertTrue(prompt.contains(COMPLETION_MARKER))
    }

    @Test
    fun testDictionary_NoInjectionWhenNoCharactersInText() = kotlinx.coroutines.runBlocking {
        // 本文に辞書掲載の登場人物が1人もいない場合、無関係な参考例は注入されず辞書ブロックがゼロになること
        val dict = NovelDict(
            style = "カタカナ",
            characters = mapOf("山田" to "ヤマダ", "佐藤" to "サトウ")
        )
        val textWithoutCharacters = "風が吹き抜ける静かな森の中、鳥たちがさえずっていた。"

        var capturedPrompt: String? = null
        val ctx = looseCtx(call = { _, prompt, _ ->
            capturedPrompt = prompt
            ok("風が吹き抜ける静かな森の中、鳥たちがさえずっていた。\n[SRC_END]")
        }).copy(dictionary = dict)

        val result = translateSingle(textWithoutCharacters, ctx)
        assertTrue(result is SingleResult.Translated)
        assertNotNull(capturedPrompt)
        assertFalse("本文に一致しない場合、辞書ルールが注入されないこと", capturedPrompt!!.contains("[人名の表記統一ルール]"))
        assertFalse("本文に一致しない場合、登場人物対応表が注入されないこと", capturedPrompt!!.contains("[登場人物対応表]"))
        assertFalse("無関係な人物名が注入されないこと", capturedPrompt!!.contains("山田"))
        assertFalse("無関係な人物名が注入されないこと", capturedPrompt!!.contains("佐藤"))
    }

    @Test
    fun testBatchFormat_Content() {
        val format = buildBatchFormat(3)
        assertTrue(format.contains("<translations>"))
        assertTrue(format.contains("1..3"))
        assertTrue(format.contains("REMINDER"))
        assertTrue(format.contains("overrides"))
        assertFalse(format.contains("[SRC_END]"))
        // Single path stays free of batch framing.
        assertFalse(buildSystemPrompt("base").contains("BATCH OUTPUT FORMAT"))
        assertTrue(
            buildSystemPrompt("base", batchFormat = buildBatchFormat(2))
                .contains("BATCH OUTPUT FORMAT")
        )
    }

    @Test
    fun testBatch_PromptCarriesFormat() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w")
        val out = store.createDir(root.uri, "out")!!
        val items = listOf("a.txt" to "第一話の原文です。", "b.txt" to "第二話の原文です。")
        val prompts = mutableListOf<String>()
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "base"),
            promptOrder = listOf(1),
            driverNames = listOf("d1"),
            verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
            call = { _, prompt, _ ->
                synchronized(prompts) { prompts.add(prompt) }
                ok("<translations><trans id=\"1\">訳文一</trans><trans id=\"2\">訳文二</trans></translations>")
            }
        )
        val outcome = translateBatch(store, out.uri, items, ctx)
        assertEquals(2, outcome.completed)
        assertTrue(prompts.isNotEmpty())
        assertTrue(prompts.all { it.contains("BATCH OUTPUT FORMAT") })
        assertTrue(prompts.all { it.contains("1..2") })
    }

    @Test
    fun testBatch_PrevTailEchoSurvives() = kotlinx.coroutines.runBlocking {
        // A model repeating the injected context is saved as-is:
        // the pipeline carries the tail but never strips an echo.
        val store = InMemoryFileStore()
        val root = store.createRoot("w")
        val out = store.createDir(root.uri, "out")!!
        val items = listOf("a.txt" to "第一話の原文です。", "b.txt" to "第二話の原文です。")
        val prompts = mutableListOf<String>()
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "base"),
            promptOrder = listOf(1),
            driverNames = listOf("d1"),
            verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
            call = { _, prompt, _ ->
                synchronized(prompts) { prompts.add(prompt) }
                ok("<translations><trans id=\"1\">前話の末尾文です。これは第一話の訳文です。</trans><trans id=\"2\">これは第二話の訳文です。</trans></translations>")
            }
        )
        val outcome = translateBatch(store, out.uri, items, ctx, prevSourceTail = "前話の原文末尾です。")
        assertEquals(2, outcome.completed)
        assertTrue(prompts.any { it.contains("PREVIOUS TEXT") })
        val saved = store.readText(store.findChild(out.uri, "a.txt")!!.uri)!!
        assertTrue(saved.contains("前話の末尾文です"))
    }

    @Test
    fun testLarge_PrevTailOnlyFirstChunk() = kotlinx.coroutines.runBlocking {
        // Chunk 1 alone receives the raw-source tail; later chunks get the
        // translated tail only. A non-echoing model leaves clean output.
        val store = InMemoryFileStore()
        val root = store.createRoot("w")
        val work = store.createDir(root.uri, "work")!!
        val out = store.createDir(root.uri, "out")!!
        val content = "これは大きなファイルの本文です。勇者が旅を続けました。\n".repeat(40)
        val prompts = mutableListOf<String>()
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "base"),
            promptOrder = listOf(1),
            driverNames = listOf("d1"),
            verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = true),
            call = { _, prompt, source ->
                synchronized(prompts) { prompts.add(prompt) }
                ok(source.trimEnd().removeSuffix("[SRC_END]").trimEnd() + "\n[SRC_END]")
            }
        )
        val done = translateLarge(
            store, work.uri, out.uri, "big.txt", content, ctx,
            LargeOptions(chunkSizeBytes = 500), prevSourceTail = "前話の原文末尾です。"
        )
        assertTrue(done is LargeOutcome.Completed)
        assertTrue(prompts.size > 1)
        assertTrue(prompts.first().contains("PREVIOUS TEXT"))
        assertTrue(prompts.drop(1).none { it.contains("PREVIOUS TEXT") })
        val final = store.readText(store.findChild(out.uri, "big.txt")!!.uri)!!
        assertTrue(final.contains("勇者"))
        assertFalse(final.contains("前話の原文末尾"))
    }

    @Test
    fun testBatch_JsonResponseParsed() {
        val jsonText = """
            ```json
            {
              "translations": [
                {"id": 1, "ja": "第一話の訳文です。"},
                {"id": 2, "ja": "第二話の訳文です。"}
              ]
            }
            ```
        """.trimIndent()
        val parsed = parseBatchJsonResponse(jsonText)
        assertNotNull(parsed)
        assertEquals("第一話の訳文です。", parsed!![1])
        assertEquals("第二話の訳文です。", parsed[2])
    }

    @Test
    fun testChunk_SurrogatePairProtected() {
        // 𠮷 (U+20BB7, UTF-16: \uD842\uDFB7)
        val surrogateChar = "\uD842\uDFB7"
        val longLine = "あ".repeat(9) + surrogateChar + "い".repeat(10)
        // 10文字境界で分割する場合、9文字目の直後にあるサロゲートペアが泣き別れにならないことを確認
        val chunks = com.example.novelscraper.translation.v2.pipeline.splitSafeOversized(longLine, 10)
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            // 不正な孤立サロゲート文字が含まれていないこと
            for (i in chunk.indices) {
                if (Character.isHighSurrogate(chunk[i])) {
                    assertTrue(i + 1 < chunk.length && Character.isLowSurrogate(chunk[i + 1]))
                }
                if (Character.isLowSurrogate(chunk[i])) {
                    assertTrue(i > 0 && Character.isHighSurrogate(chunk[i - 1]))
                }
            }
        }
    }

    @Test
    fun testQuality_LineCountOkFailsOnEmptyOutput() {
        val src = "1行目\n2行目\n3行目\n4行目\n5行目\n6行目"
        val emptyDst = ""
        assertFalse(com.example.novelscraper.translation.v2.pipeline.lineCountOk(src, emptyDst))
        val blankDst = "   \n  \n  "
        assertFalse(com.example.novelscraper.translation.v2.pipeline.lineCountOk(src, blankDst))
    }

    @Test
    fun testLargeOptions_MaxInputBytesIs10MB() {
        assertEquals(10_000_000, com.example.novelscraper.translation.v2.pipeline.LargeOptions().maxInputBytes)
        assertEquals(10_000_000, com.example.novelscraper.translation.v2.engine.EngineOptions().maxInputBytes)
    }

    @Test
    fun testDictStage_OversizedFileIsChunked() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val workDir = store.createRoot("dict_chunk_test")
        val bigFileContent = "段落1の内容です。".repeat(500) + "\n\n" + "段落2の内容です。".repeat(500)
        val fileNames = listOf("big_novel.txt")
        val batchesReceived = mutableListOf<String>()

        val options = com.example.novelscraper.translation.v2.pipeline.DictOptions(
            maxBatchBytes = 3000,
            maxTotalScanBytes = 10000
        )

        val result = com.example.novelscraper.translation.v2.pipeline.generateDictionary(
            store = store,
            workDirUri = workDir.uri,
            fileNames = fileNames,
            readText = { bigFileContent },
            call = { _, _, text ->
                batchesReceived.add(text)
                ok("""{"characters": [{"original": "主人公", "japanese": "主人公"}]}""")
            },
            options = options
        )

        assertNotNull(result)
        assertTrue("バッチに分割されていること", batchesReceived.size > 1)
        for (batch in batchesReceived) {
            assertTrue("各バッチがmaxBatchBytes近傍で上限遵守していること", com.example.novelscraper.translation.v2.pipeline.utf8Bytes(batch) <= options.maxBatchBytes + 100)
        }
    }

    @Test
    fun testUtf8Bytes_ParityWithStandardByteArray() {
        val testStrings = listOf(
            "",
            "hello world",
            "こんにちは世界",
            "中文测试",
            "한국어 테스트",
            "Emoji: 🚀 📖 ✅ ❌ ⚠️ 🔄",
            "Special symbols: \u0000 \t \n \r \uFEFF \u200B",
            "Mixed: Hello 日本語 🇨🇳 🇰🇷 🔥 (test) [123]"
        )
        for (str in testStrings) {
            val expected = str.toByteArray(Charsets.UTF_8).size
            val actual = com.example.novelscraper.translation.v2.pipeline.utf8Bytes(str)
            assertEquals("Length mismatch for: $str", expected, actual)
        }
    }

    @Test
    fun testRefine_OffKeepsFirstWithoutExtraCall() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val ctx = looseCtx(call = { _, _, source ->
            calls++
            ok(source)
        })
        val out = com.example.novelscraper.translation.v2.pipeline.polishTranslation(
            "原文です。",
            "初回訳です。",
            ctx,
            VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false)
        )
        assertEquals("初回訳です。", out)
        assertEquals(0, calls)
    }

    @Test
    fun testRefine_OnReplacesWhenVerified() = kotlinx.coroutines.runBlocking {
        val base = looseCtx(call = { _, _, source -> ok(source) })
        val ctx = base.copy(refine = RefineConfig("polish it") { _, source -> ok(source) })
        val out = com.example.novelscraper.translation.v2.pipeline.polishTranslation(
            "原文です。",
            "初回訳です。",
            ctx,
            VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false)
        )
        // fakeは入力をそのまま返すため、磨き文は推敲入力そのものになる
        assertTrue(out.contains("初回訳です。"))
        assertTrue(out.contains("=== SOURCE"))
    }

    @Test
    fun testRefine_FailureKeepsFirst() = kotlinx.coroutines.runBlocking {
        val base = looseCtx(call = { _, _, _ -> fail(FailureKind.RETRYABLE_AFTER) })
        val ctx = base.copy(refine = RefineConfig("polish it") { _, _ -> fail(FailureKind.RETRYABLE_AFTER) })
        val out = com.example.novelscraper.translation.v2.pipeline.polishTranslation(
            "原文です。",
            "初回訳です。",
            ctx,
            VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false)
        )
        assertEquals("初回訳です。", out)
    }

    @Test
    fun testRefine_RejectKeepsFirst() = kotlinx.coroutines.runBlocking {
        // 磨き文が空＝検証不合格のため初回訳を採用する
        val base = looseCtx(call = { _, _, _ -> ok("   ") })
        val ctx = base.copy(refine = RefineConfig("polish it") { _, _ -> ok("   ") })
        val out = com.example.novelscraper.translation.v2.pipeline.polishTranslation(
            "原文です。",
            "初回訳です。",
            ctx,
            VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false)
        )
        assertEquals("初回訳です。", out)
    }

    @Test
    fun testRefine_SingleAppliesGlossaryTerms() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("李云" to "李雲"))
        var refineInput = ""
        val ctx = TranslateContext(
            basePrompts = mapOf(1 to "base"),
            promptOrder = listOf(1),
            driverNames = listOf("d1"),
            dictionary = dict,
            verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
            refine = RefineConfig("polish it") { _, source ->
                refineInput = source
                ok("李雲が旅立った。")
            },
            call = { _, _, _ -> ok("李雲が旅立った。") }
        )
        val r = translateSingle("李云出发了。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertTrue((r as SingleResult.Translated).text.contains("李雲"))
        assertTrue(refineInput.contains("李云 → 李雲"))
    }

    @Test
    fun testRefine_ResolveBlankFallsBackToDefault() {
        val resolved = com.example.novelscraper.translation.v2.pipeline.resolveRefinePrompt("   ")
        assertEquals(com.example.novelscraper.translation.v2.pipeline.DEFAULT_REFINE_PROMPT, resolved)
        val custom = com.example.novelscraper.translation.v2.pipeline.resolveRefinePrompt("磨いて")
        assertEquals("磨いて", custom)
    }

    @Test
    fun testDictStage_SingleCharNamesDroppedBeforeTranslate() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("d-single")
        val defaults = com.example.novelscraper.translation.v2.pipeline.DictPrompts()
        var translateNames: List<String>? = null
        val files = listOf("a.txt" to "阿离和老周来了。", "b.txt" to "李云龙也来了。")
        val byName = files.toMap()
        val dict = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, prompt, text ->
                if (prompt == defaults.batch) ok("""{"names":["阿离","离","周","李云龙"]}""")
                else if (prompt == defaults.merge) ok(text)
                else {
                    translateNames = parseExtractedNames(text)?.names
                    ok("""{"style":"漢字","characters":{"阿离":"阿離","李云龙":"李雲龍"},"genders":{}}""")
                }
            },
            DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNotNull(dict)
        assertEquals(listOf("阿离", "李云龙"), translateNames)
        assertFalse(dict!!.characters.keys.any { it.length < 2 })
        assertEquals("李雲龍", dict.characters["李云龙"])
    }

    @Test
    fun testExtractedNames_AuthorsParsed() {
        val withAuthors = parseExtractedNames("""{"names":["李云龙"],"authors":["田隶"]}""")!!
        assertEquals(listOf("李云龙"), withAuthors.names)
        assertEquals(listOf("田隶"), withAuthors.authors)
        val legacy = parseExtractedNames("""{"names":["李云龙"]}""")!!
        assertEquals(listOf("李云龙"), legacy.names)
        assertTrue(legacy.authors.isEmpty())
        val topArray = parseExtractedNames("""["李云龙"]""")!!
        assertEquals(listOf("李云龙"), topArray.names)
        assertTrue(topArray.authors.isEmpty())
        val broken = parseExtractedNames("""{"names":["李云龙"],"authors":"田隶"}""")!!
        assertEquals(listOf("李云龙"), broken.names)
        assertTrue(broken.authors.isEmpty())
        assertTrue(broken.hints.isEmpty())
    }

    @Test
    fun testExtractedNames_HintsParsed() {
        // 配列形式・文字列形式の両方を受理し、旧形式（キーなし）は空扱い。
        val arrayForm = parseExtractedNames(
            """{"names":["李云龙"],"hints":{"李云龙":["落ち着いた宗主の少年","冷静な青年"]}}"""
        )!!
        assertEquals(listOf("落ち着いた宗主の少年", "冷静な青年"), arrayForm.hints["李云龙"])
        val stringForm = parseExtractedNames(
            """{"names":["李云龙"],"hints":{"李云龙":"落ち着いた宗主の少年"}}"""
        )!!
        assertEquals(listOf("落ち着いた宗主の少年"), stringForm.hints["李云龙"])
        val legacy = parseExtractedNames("""{"names":["李云龙"],"authors":[]}""")!!
        assertTrue(legacy.hints.isEmpty())
    }

    @Test
    fun testCollectProfileHints() {
        // 同名束ね・空除外・名簿外除外・上限切りを検証。
        val batches = listOf(
            com.example.novelscraper.translation.v2.pipeline.ExtractedNames(
                names = listOf("李云", "江思"),
                hints = mapOf("李云" to listOf("落ち着いた宗主", "冷静な青年"), "幽霊" to listOf("出ない人物"), "江思" to listOf("  "))
            ),
            com.example.novelscraper.translation.v2.pipeline.ExtractedNames(
                names = listOf("李云"),
                hints = mapOf("李云" to listOf("冷静な青年", "h4", "h5"))
            )
        )
        val table = collectProfileHints(batches, maxPerName = 3)
        assertEquals(listOf("落ち着いた宗主", "冷静な青年", "h4"), table["李云"])
        assertNull(table["幽霊"])
        assertNull(table["江思"])
        // 既定上限は1名5件。
        val many = collectProfileHints(
            listOf(
                com.example.novelscraper.translation.v2.pipeline.ExtractedNames(
                    names = listOf("甲"),
                    hints = mapOf("甲" to (1..7).map { "素$it" })
                )
            )
        )
        assertEquals(5, many["甲"]!!.size)
    }

    @Test
    fun testTransferShortHints() {
        // ·式短形の落選分だけフルネーム側へ畳み、非·式の落選は捨てる。
        val table = mapOf(
            "李维" to listOf("参謀少女"),
            "太郎" to listOf("別人メモ"),
            "李维·史奈克" to listOf("フル側メモ")
        )
        val out = transferShortHints(
            survivors = setOf("李维·史奈克", "太郎"),
            dropped = listOf("李维", "田中"),
            table = table
        )
        assertEquals(listOf("フル側メモ", "参謀少女"), out["李维·史奈克"])
        assertEquals(listOf("別人メモ"), out["太郎"])
        assertFalse(out.containsKey("田中"))
        // 原本は不変（pure）。
        assertEquals(listOf("参謀少女"), table["李维"])
    }

    @Test
    fun testSelectHintsForTranslate_BudgetCap() {
        // 総量超過分は後方切り捨て。前方は全保持。
        val table = mapOf("a" to listOf("12345"), "b" to listOf("12345"), "c" to listOf("12345"))
        assertEquals(setOf("a", "b"), selectHintsForTranslate(listOf("a", "b", "c"), table, maxTotalChars = 10).keys)
        assertEquals(setOf("a", "b", "c"), selectHintsForTranslate(listOf("a", "b", "c"), table).keys)
        assertTrue(selectHintsForTranslate(listOf("a"), emptyMap()).isEmpty())
    }

    @Test
    fun testDictionary_HintsFlowToProfiles() = kotlinx.coroutines.runBlocking {
        // 抽出の素→束ね→命名合成→確定辞書のprofilesまで一気通貫すること。
        val store = InMemoryFileStore()
        val root = store.createRoot("d-hints")
        val defaults = com.example.novelscraper.translation.v2.pipeline.DictPrompts()
        var translateInput = ""
        val files = listOf("a.txt" to "李云和江思来了。", "b.txt" to "李云又来了。")
        val byName = files.toMap()
        val dict = generateDictionary(
            store, root.uri, files.map { it.first },
            readText = { byName[it] },
            call = { _, prompt, text ->
                if (prompt == defaults.batch) {
                    ok("""{"names":["李云","江思"],"hints":{"李云":["落ち着いた宗主の少年"]}}""")
                } else if (prompt == defaults.merge) {
                    ok(text)
                } else {
                    translateInput = text
                    ok("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思"},"profiles":{"李云":"落ち着いた宗主の少年"}}""")
                }
            },
            DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNotNull(dict)
        assertEquals("落ち着いた宗主の少年", dict!!.profiles["李云"])
        assertTrue(translateInput.contains("落ち着いた宗主の少年"))
    }

    @Test
    fun testDictPrompts_ResolveAndHash() {
        val defaults = com.example.novelscraper.translation.v2.pipeline.DictPrompts()
        val empty = com.example.novelscraper.translation.v2.pipeline.resolveDictPrompts(com.example.novelscraper.translation.v2.settings.V2DictPrompts())
        assertEquals(defaults, empty)
        val blank = com.example.novelscraper.translation.v2.pipeline.resolveDictPrompts(com.example.novelscraper.translation.v2.settings.V2DictPrompts("  ", "\n", ""))
        assertEquals(defaults, blank)
        val custom = com.example.novelscraper.translation.v2.pipeline.resolveDictPrompts(com.example.novelscraper.translation.v2.settings.V2DictPrompts("B", "M", "T"))
        assertEquals(com.example.novelscraper.translation.v2.pipeline.DictPrompts("B", "M", "T"), custom)
        val partial = com.example.novelscraper.translation.v2.pipeline.resolveDictPrompts(com.example.novelscraper.translation.v2.settings.V2DictPrompts(translate = "Tのみ"))
        assertEquals(defaults.batch, partial.batch)
        assertEquals(defaults.merge, partial.merge)
        assertEquals("Tのみ", partial.translate)
        val over = com.example.novelscraper.translation.v2.pipeline.resolveDictPrompts(com.example.novelscraper.translation.v2.settings.V2DictPrompts(batch = "x".repeat(TranslationLimits.MAX_DICT_PROMPT_CHARS + 10)))
        assertEquals(TranslationLimits.MAX_DICT_PROMPT_CHARS, over.batch.length)
        assertEquals(com.example.novelscraper.translation.v2.pipeline.dictPromptsHash(defaults), com.example.novelscraper.translation.v2.pipeline.dictPromptsHash(com.example.novelscraper.translation.v2.pipeline.DictPrompts()))
        assertNotEquals(com.example.novelscraper.translation.v2.pipeline.dictPromptsHash(defaults), com.example.novelscraper.translation.v2.pipeline.dictPromptsHash(defaults.copy(batch = "B")))
        val old = com.example.novelscraper.translation.v2.pipeline.parseNovelDictLenient("""{"style":"漢字","characters":{"李云":"李雲"}}""")
        assertNotNull(old)
        assertEquals("", old!!.promptsHash)
        assertNotEquals(com.example.novelscraper.translation.v2.pipeline.dictPromptsHash(defaults), old.promptsHash)
        val stamped = old.copy(promptsHash = com.example.novelscraper.translation.v2.pipeline.dictPromptsHash(defaults))
        assertEquals(stamped, com.example.novelscraper.translation.v2.pipeline.parseNovelDictLenient(com.example.novelscraper.translation.v2.pipeline.encodeNovelDict(stamped)))
        val settingsJson = com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository.v2Json
        val withPrompts = V2Settings(dict = com.example.novelscraper.translation.v2.settings.V2DictSettings(dictPrompts = com.example.novelscraper.translation.v2.settings.V2DictPrompts(batch = "B")))
        val decoded = settingsJson.decodeFromString(V2Settings.serializer(), settingsJson.encodeToString(V2Settings.serializer(), withPrompts))
        assertEquals("B", decoded.dict.dictPrompts.batch)
        assertEquals("", decoded.dict.dictPrompts.merge)
        val legacy = settingsJson.decodeFromString(V2Settings.serializer(), "{}")
        assertEquals(com.example.novelscraper.translation.v2.settings.V2DictPrompts(), legacy.dict.dictPrompts)
        assertEquals(1, legacy.dict.dictPromptsVersion)
    }

    @Test
    fun testDictAnnotatablePredicate() {
        assertTrue(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("李维", "レヴィ"))
        assertTrue(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("田隶", "田隷"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("文", "文"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("小灰", "小灰"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("离", "離"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("尘", "塵"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("安", "安"))
    }

    @Test
    fun testPlanBundles_SequentialAndSkips() {
        fun doc(name: String, length: Long) = VDoc("u/$name", name, false, length)
        val files = listOf(doc("a.txt", 100L), doc("b.txt", 100L), doc("c.txt", 100L), doc("d.txt", 100L), doc("e.txt", 100L))
        val basic = com.example.novelscraper.translation.v2.pipeline.planBundles(files, { false }, batchMaxFiles = 3, batchMaxBytes = 10000, splitThresholdBytes = 10000)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), basic.map { it.indices })
        val skipped = com.example.novelscraper.translation.v2.pipeline.planBundles(files, { it.name == "b.txt" || it.name == "d.txt" }, batchMaxFiles = 3, batchMaxBytes = 10000, splitThresholdBytes = 10000)
        assertEquals(listOf(listOf(0, 2, 4)), skipped.map { it.indices })
        val capped = com.example.novelscraper.translation.v2.pipeline.planBundles(files, { false }, batchMaxFiles = 3, batchMaxBytes = 250, splitThresholdBytes = 10000)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), capped.map { it.indices })
        val mixed = listOf(doc("s.txt", 100L), doc("big.txt", 50000L), doc("u.txt", 0L), doc("t.txt", 100L))
        val singles = com.example.novelscraper.translation.v2.pipeline.planBundles(mixed, { false }, { it.name == "t.txt" }, 3, 10000, 10000)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), singles.map { it.indices })
        val pool = com.example.novelscraper.translation.v2.pipeline.BundlePool(basic)
        assertEquals(listOf(0, 1, 2), pool.claimNext()!!.indices)
        assertEquals(listOf(3, 4), pool.claimNext()!!.indices)
        assertNull(pool.claimNext())
    }

    @Test
    fun testDictStage_AuthorNamesExcluded() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("d-author")
        val defaults = com.example.novelscraper.translation.v2.pipeline.DictPrompts()
        val logs = mutableListOf<String>()
        val files = listOf("a.txt" to "李云龙大战。", "b.txt" to "作者：田隶。")
        val byName = files.toMap()
        val dict = generateDictionary(
            store = store,
            workDirUri = root.uri,
            fileNames = files.map { it.first },
            readText = { byName[it] },
            call = { _, prompt, text ->
                if (prompt == defaults.batch) ok("""{"names":["李云龙"],"authors":["田隶"]}""")
                else if (prompt == defaults.merge) ok(text)
                else ok("""{"style":"漢字","characters":{"李云龙":"李雲龍"},"genders":{}}""")
            },
            options = DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2),
            log = { synchronized(logs) { logs.add(it) } }
        )
        assertNotNull(dict)
        assertEquals(mapOf("李云龙" to "李雲龍"), dict!!.characters)
        assertTrue(logs.any { it.contains("作者として除外") && it.contains("田隶") })
    }

    @Test
    fun testTermAnnotation_AnnotateAndStrip() {
        val terms = mapOf("李云" to "李雲", "李云龙" to "李雲龍")
        val ann = com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation("李云龙が叫んだ")
        assertNotNull(ann)
        assertEquals("⟦", ann!!.open)
        assertEquals("李云龙⟦李雲龍⟧と李云⟦李雲⟧が来た", com.example.novelscraper.translation.v2.pipeline.annotateSourceTerms("李云龙と李云が来た", terms, ann))
        assertEquals("李雲龍と李雲が来た", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("李云龙⟦李雲龍⟧と李云⟦李雲⟧が来た", terms, ann))
        assertEquals("李雲龍が来た", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("⟦李雲龍⟧が来た", terms, ann))
        assertEquals("あいうえお李雲龍", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("あいうえお⟦李雲龍⟧", terms, ann))
        assertEquals("通常文", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("通常文", terms, ann))
        assertEquals("李雲龍が来た", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("李云龙⟦リー・ユン⟧が来た", terms, ann))
        assertEquals("注補足文", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("注⟦補足⟧文", terms, ann))
        val tricky = mapOf("甲" to "A\$B\\C")
        assertEquals("A\$B\\Cが来た", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("甲⟦旧⟧が来た", tricky, ann))
        assertEquals("李云龙⟦X⟧", com.example.novelscraper.translation.v2.pipeline.stripTermAnnotations("李云龙⟦X⟧", emptyMap(), ann))
        assertNull(com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation("⟦a⟧⦅b⦆❰c❱⦃d⦄⦑e⦒⟪f⟫"))
        val fallback = com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation("⟦a⟧⦅b⦆❰c❱")
        assertNotNull(fallback)
        assertEquals("⦃", fallback!!.open)
        assertEquals("本文", com.example.novelscraper.translation.v2.pipeline.annotateSourceTerms("本文", emptyMap(), ann))
        assertEquals(mapOf("李云" to "李雲", "李云龙" to "李雲龍"), com.example.novelscraper.translation.v2.pipeline.matchDictionaryMap("李云龙が叫んだ", terms + ("不存在" to "X")))
    }

    @Test
    fun testTermAnnotation_CollapseDuplicatedTerms() {
        val strip = ::stripTermAnnotations
        val ann = com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation("本文")!!
        val terms = mapOf("冰糖" to "氷糖", "银莲" to "銀蓮", "李云" to "李雲", "李云龙" to "李雲龍")
        // 注釈エコー（訳⟦訳⟧）は畳まれて1つになる
        assertEquals("氷糖を分ける", strip("氷糖⟦氷糖⟧を分ける", terms, ann))
        // 直書きの二重・三重も畳まれる
        assertEquals("銀蓮が来た", strip("銀蓮銀蓮が来た", terms, ann))
        assertEquals("銀蓮が来た", strip("銀蓮銀蓮銀蓮が来た", terms, ann))
        assertEquals("李雲龍が来た", strip("李雲龍李雲龍が来た", terms, ann))
        // 区切り挟み・単発・辞書外・1字値は触らない
        assertEquals("李雲、李雲が来た", strip("李雲、李雲が来た", terms, ann))
        assertEquals("李雲が来た", strip("李雲が来た", terms, ann))
        assertEquals("ダメダメだ", strip("ダメダメだ", terms, ann))
        assertEquals("AA来た", strip("AA来た", mapOf("甲" to "A"), ann))
        // 通常の注釈剥離は従来通り
        assertEquals("氷糖を分ける", strip("冰糖⟦氷糖⟧を分ける", terms, ann))
    }

    @Test
    fun testDictAliases_ExpandShortForms() {
        val chars = mapOf("李维·史奈克" to "レヴィ・スネーク", "索德·史奈克" to "ソード・スネーク")
        assertEquals(mapOf("李维" to "レヴィ"), com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("李维走进大厅。", chars))
        assertEquals(mapOf("史奈克" to "スネーク"), com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("史奈克家族的徽章。", chars))
        assertTrue(com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("甘文が来た。", mapOf("甘·文" to "カン・ブン")).isEmpty())
        assertTrue(com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("阿Bが来た。", mapOf("阿·B" to "エービー")).isEmpty())
        assertTrue(com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("李维走进大厅。", chars, exclude = setOf("李维")).isEmpty())
        val conflicts = mutableListOf<String>()
        val dup = com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("西Cが来た。", mapOf("東A·西C" to "トウA・セイC", "南B·西C" to "ナンB・サイC"), onConflict = { conflicts.add(it) })
        assertEquals(mapOf("西C" to "セイC"), dup)
        assertEquals(listOf("西C"), conflicts)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.matchDictionaryAliases("小灰が来た。", mapOf("小灰·X" to "小灰·Y")).isEmpty())
    }

    @Test
    fun testDictMismatch_MustContainDeterminedReadings() {
        fun vo(check: com.example.novelscraper.translation.v2.pipeline.DictCheck) =
            VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false, dictCheck = check)
        val ann = com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation("李维x史奈克")!!
        val strip = com.example.novelscraper.translation.v2.pipeline.DictStrip(mapOf("李维" to "レヴィ", "史奈克" to "スネーク"), ann)
        val check = com.example.novelscraper.translation.v2.pipeline.DictCheck(strip.terms, strip, strip.terms)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", "レヴィとスネークが来た。", vo(check)).complete)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", "李维⟦レヴィ⟧と史奈克⟦スネーク⟧が来た。", vo(check)).complete)
        val r = com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", "李維とフォックスが来た。", vo(check))
        assertFalse(r.complete)
        assertTrue(r.note.startsWith("dict-mismatch"))
        assertTrue(r.note.contains("残存0/2件"))
        val plain = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", "何か文。", plain).complete)
        val blank = com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", "   ", vo(check))
        assertFalse(blank.complete)
        assertEquals("blank", blank.note)
    }

    @Test
    fun testBatch_UnclosedTailSegmentNotRescued() {
        val cutText = "<trans id=\"1\">第一話の訳文です。</trans>\n<trans id=\"2\">第二話の途中まで生成された訳文"
        val parsed = parseBatchResponse(cutText)
        assertNotNull(parsed)
        assertEquals("第一話の訳文です。", parsed!![1])
        assertNull(parsed!![2])
    }

    @Test
    fun testTranslateSingle_ExpandsAliasesEndToEnd() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("李维·史奈克" to "レヴィ・スネーク"))
        var capturedSource: String? = null
        val ctx = looseCtx(call = { _, _, source ->
            capturedSource = source
            ok(source)
        }).copy(dictionary = dict)
        val r = translateSingle("李维走进大厅。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals("李维⟦レヴィ⟧走进大厅。", capturedSource)
        assertEquals("レヴィ走进大厅。", (r as SingleResult.Translated).text)
    }

    @Test
    fun testDictEchoEntries_SkippedEntirely() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("文" to "文", "李维" to "レヴィ"))
        var capturedSource: String? = null
        val ctx = looseCtx(call = { _, _, source ->
            capturedSource = source
            ok("レヴィは祝日だ。")
        }).copy(dictionary = dict)
        val r = translateSingle("李维は文化の日だ。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals("李维⟦レヴィ⟧は文化の日だ。", capturedSource)
        assertEquals("レヴィは祝日だ。", (r as SingleResult.Translated).text)
    }

    @Test
    fun testDictSingleCharEntries_SkippedWithoutCorruption() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("离" to "離", "李维" to "レヴィ"))
        var capturedSource: String? = null
        val ctx = looseCtx(call = { _, _, source ->
            capturedSource = source
            ok("レヴィは大広間を去った。")
        }).copy(dictionary = dict)
        val r = translateSingle("李维离开大厅。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals("李维⟦レヴィ⟧离开大厅。", capturedSource)
        assertEquals("レヴィは大広間を去った。", (r as SingleResult.Translated).text)
    }

    @Test
    fun testTranslateSingle_DictMismatchFailsTransient() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("李维" to "レヴィ", "史奈克" to "スネーク"))
        val ctx = looseCtx(call = { _, _, _ ->
            ok("李維とフォックスが叫んだ。")
        }).copy(dictionary = dict)
        val r = translateSingle("李维と史奈克が叫んだ。", ctx)
        assertTrue(r is SingleResult.Failed)
        val f = r as SingleResult.Failed
        assertFalse("赤札化しないこと", f.isDeterministic)
        assertTrue(f.note.contains("dict-mismatch"))
        assertFalse(shouldPersistFailed(f))
    }

    @Test
    fun testTranslateSingle_DictMismatchToleratesOne() = kotlinx.coroutines.runBlocking {
        // 必須2件中1件欠けは合格する（8/9通過）
        val dict = NovelDict(characters = mapOf("李维" to "レヴィ", "史奈克" to "スネーク"))
        val ctx = looseCtx(call = { _, _, _ ->
            ok("レヴィとフォックスが叫んだ。")
        }).copy(dictionary = dict)
        val r = translateSingle("李维と史奈克が叫んだ。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertTrue((r as SingleResult.Translated).text.contains("レヴィ"))
    }

    @Test
    fun testTranslateLarge_DictMismatchHoldsWithoutFailedFile() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("w_mismatch")
        val work = store.createDir(root.uri, ".parts_mm")!!
        val out = store.createDir(root.uri, "out")!!
        val dict = NovelDict(characters = mapOf("李维" to "レヴィ", "史奈克" to "スネーク"))
        val ctx = looseCtx(call = { _, _, _ ->
            ok("李維とフォックスが叫んだ。")
        }).copy(dictionary = dict)
        val outcome = translateLarge(store, work.uri, out.uri, "mm.txt", "李维と史奈克が叫んだ。", ctx, LargeOptions(chunkSizeBytes = 100000))
        assertTrue(outcome is LargeOutcome.Held)
        assertTrue((outcome as LargeOutcome.Held).reason.contains("一時的失敗"))
        var failedFound = 0
        for (child in store.children(work.uri)) {
            val docs = if (child.isDirectory) store.children(child.uri) else listOf(child)
            if (docs.any { it.name.endsWith(".failed") }) failedFound = 1
        }
        assertFalse("赤札を作らないこと", failedFound != 0)
    }

    @Test
    fun testTranslateSingle_AnnotatesTermsAndStripsEcho() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("李云龙" to "李雲龍"))
        var capturedSource: String? = null
        var capturedPrompt: String? = null
        val ctx = looseCtx(call = { _, prompt, source ->
            capturedPrompt = prompt
            capturedSource = source
            ok("李雲龍が叫んだ。")
        }).copy(dictionary = dict)
        val r = translateSingle("李云龙が叫んだ。", ctx)
        assertTrue(r is SingleResult.Translated)
        assertEquals("李云龙⟦李雲龍⟧が叫んだ。", capturedSource)
        assertTrue(capturedPrompt!!.contains("[確定訳語]"))
        assertFalse(capturedPrompt!!.contains("[人物対応表]"))
        val ctx2 = looseCtx(call = { _, _, _ ->
            ok("李云龙⟦李雲龍⟧が叫んだ。")
        }).copy(dictionary = dict)
        val r2 = translateSingle("李云龙が叫んだ。", ctx2)
        assertTrue(r2 is SingleResult.Translated)
        assertEquals("李雲龍が叫んだ。", (r2 as SingleResult.Translated).text)
    }

    @Test
    fun testDictPolicy_ForLangMapping() {
        assertTrue(com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.KO) is com.example.novelscraper.translation.v2.pipeline.DictPolicy.Ko)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.ZH) is com.example.novelscraper.translation.v2.pipeline.DictPolicy.Default)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.EN) is com.example.novelscraper.translation.v2.pipeline.DictPolicy.En)
        assertTrue(com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.JA) is com.example.novelscraper.translation.v2.pipeline.DictPolicy.Default)
        assertEquals("対応表", com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.KO).logLabel())
        assertEquals("注釈", com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.ZH).logLabel())
    }

    @Test
    fun testDictPolicy_RequiredTerms() {
        val terms = mapOf("사재혁" to "サ・ジェヒョク", "혁이" to "ヒョギ")
        val koPolicy = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.KO)
        assertEquals(setOf("사재혁"), koPolicy.check(terms, null).required.keys)
        val zh = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.ZH)
        val ann = com.example.novelscraper.translation.v2.pipeline.selectTermAnnotation("x")!!
        assertEquals(terms.keys, zh.check(terms, ann).required.keys)
        assertTrue(zh.check(terms, null).required.isEmpty())
        assertTrue(koPolicy.check(emptyMap<String, String>(), null).required.isEmpty())
    }

    @Test
    fun testDictPolicy_MatchParityWithLegacy() {
        val chars = mapOf("李云" to "李雲", "李云龙" to "李雲龍")
        val def = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.ZH)
        assertEquals(matchDictionaryMap("李云龙が来た", chars), def.matchTerms("李云龙が来た", chars))
        val ko = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.KO)
        assertEquals(
            com.example.novelscraper.translation.v2.pipeline.matchDictionaryMapKo("사재혁이 왔다", mapOf("사재혁" to "サ・ジェヒョク")),
            ko.matchTerms("사재혁이 왔다", mapOf("사재혁" to "サ・ジェヒョク"))
        )
        // 動作例：文法の中身は拾わず、文節先頭の人物は拾う
        assertFalse(ko.matchTerms("물이나 떠와", mapOf("이나" to "イナ")).containsKey("이나"))
        assertTrue(ko.matchTerms("이나가 말했다", mapOf("이나" to "イナ")).containsKey("이나"))
        assertTrue(def.matchAliases("李维走进大厅。", mapOf("李维·史奈克" to "レヴィ・スネーク")).isNotEmpty())
        assertTrue(ko.matchAliases("李维走进大厅。", mapOf("李维·史奈克" to "レヴィ・スネーク")).isEmpty())
    }

    @Test
    fun testDictPolicy_PrepareShapes() {
        val terms = mapOf("李云" to "李雲")
        val def = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.ZH)
        val pa = def.prepare("李云が来た", terms)
        assertNotNull(pa.annotation)
        assertNull(pa.glossary)
        assertTrue(pa.sendText.contains("⟦李雲⟧"))
        val ko = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.KO)
        val pk = ko.prepare("이나가 말했다", mapOf("이나" to "イナ"))
        assertNull(pk.annotation)
        assertEquals(mapOf("이나" to "イナ"), pk.glossary)
        assertEquals("이나가 말했다", pk.sendText)
        assertNull(def.prepare("本文", emptyMap()).annotation)
        assertNull(ko.prepare("本文", emptyMap()).glossary)
    }

    @Test
    fun testDictPolicy_EnBoundaryMatch() {
        val en = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.EN)
        val marks = mapOf("Mark" to "マーク")
        // 動作例：一般語の中身は拾わず、独立した人名は拾う
        assertFalse(en.matchTerms("Market opens at nine.", marks).containsKey("Mark"))
        assertTrue(en.matchTerms("Mark came home.", marks).containsKey("Mark"))
        assertTrue(en.matchTerms("Hey, Mark!", marks).containsKey("Mark"))
        assertTrue(en.matchTerms("John's book is thick.", mapOf("John" to "ジョン")).containsKey("John"))
        // 動作例：大文字小文字は区別する（文中の助動詞は拾わない）
        assertFalse(en.matchTerms("I will go there.", mapOf("Will" to "ウィル")).containsKey("Will"))
        assertTrue(en.matchTerms("Will you come?", mapOf("Will" to "ウィル")).containsKey("Will"))
        assertFalse(en.matchTerms("The Wills protested.", mapOf("Will" to "ウィル")).containsKey("Will"))
        // 対応表方式（原文温存・注釈なし）
        val prepared = en.prepare("Mark came home.", marks)
        assertNull(prepared.annotation)
        assertEquals(mapOf("Mark" to "マーク"), prepared.glossary)
        assertEquals("Mark came home.", prepared.sendText)
        assertEquals("対応表", en.logLabel())
        assertTrue(en.matchAliases("Mark came.", marks).isEmpty())
    }

    @Test
    fun testDictPolicy_EnRequiredLenientShort() {
        val en = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.EN)
        val check = en.check(mapOf("Alexander" to "アレクサンダー", "Al" to "アル"), null)
        assertEquals(setOf("Alexander"), check.required.keys)
        assertEquals(setOf("Alexander", "Al"), check.terms.keys)
        assertNull(check.strip)
    }

    @Test
    fun testRefine_EnGlossaryEndToEnd() = kotlinx.coroutines.runBlocking {
        val dict = NovelDict(characters = mapOf("Arthur" to "アーサー"))
        var refineInput = ""
        val ctx = TranslateContext(
            basePrompts = mapOf(2 to "base"),
            promptOrder = listOf(2),
            driverNames = listOf("d1"),
            dictionary = dict,
            sourceLang = SourceLang.EN,
            verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
            refine = RefineConfig("polish it") { _, source ->
                refineInput = source
                ok("アーサーが旅立った。")
            },
            call = { _, _, _ -> ok("アーサーが旅立った。") }
        )
        val r = translateSingle("Arthur left for the journey.", ctx)
        assertTrue(r is SingleResult.Translated)
        assertTrue((r as SingleResult.Translated).text.contains("アーサー"))
        // 推敲入力に原文・初回訳・対応表の3点が入ること
        assertTrue(refineInput.contains("Arthur left for the journey."))
        assertTrue(refineInput.contains("アーサーが旅立った。"))
        assertTrue(refineInput.contains("Arthur → アーサー"))
    }

    @Test
    fun testDictPolicy_KoShortAlternates() {
        val ko = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.KO)
        // 基本：フル→短形の許容読みが出る
        val alts = ko.alternates(mapOf("진예서" to "ジン・イェソ", "윤채원" to "ユン・チェウォン"))
        assertEquals(listOf("イェソ"), alts["진예서"])
        assertEquals(listOf("チェウォン"), alts["윤채원"])
        // 2文字名・中黒なし・空値は対象外
        assertTrue(ko.alternates(mapOf("이나" to "イナ")).isEmpty())
        assertTrue(ko.alternates(mapOf("가나" to "カナ")).isEmpty())
        assertTrue(ko.alternates(mapOf("abc" to "")).isEmpty())
        // 章内に短形名の本人がいる時は不採用
        assertTrue(ko.alternates(mapOf("진예서" to "ジン・イェソ", "예서" to "イェソ")).isEmpty())
        // 同じ短形に別読みが2者は不採用
        assertTrue(
            ko.alternates(mapOf("진예서" to "ジン・イェソ", "한예서" to "ハン・エソ")).isEmpty()
        )
        // 既定方式は許容読みなし
        val def = com.example.novelscraper.translation.v2.pipeline.DictPolicy.forLang(SourceLang.ZH)
        assertTrue(def.alternates(mapOf("李云龙" to "李雲龍")).isEmpty())
    }

    @Test
    fun testDictCheck_ToleratesOneMissing() {
        fun vo(check: com.example.novelscraper.translation.v2.pipeline.DictCheck) =
            VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false, dictCheck = check)
        val terms = (1..9).associate { "K$it" to "V$it" }
        val check = com.example.novelscraper.translation.v2.pipeline.DictCheck(terms, null, terms)
        // 8/9通過
        val eight = (1..8).joinToString("") { "V$it" }
        assertTrue(com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", eight, vo(check)).complete)
        // 7/9は不合格
        val seven = (1..7).joinToString("") { "V$it" }
        val r = com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", seven, vo(check))
        assertFalse(r.complete)
        assertTrue(r.note.contains("残存7/9件"))
        // 必須1件の1欠けも許容する
        val solo = com.example.novelscraper.translation.v2.pipeline.DictCheck(mapOf("K" to "V"), null, mapOf("K" to "V"))
        assertTrue(com.example.novelscraper.translation.v2.pipeline.assessCompletion("src", "関係ない文。", vo(solo)).complete)
    }

    @Test
    fun testTranslateSingle_KoShortAndTolerated() = kotlinx.coroutines.runBlocking {
        // 動作例：37話型。短形イェソで必須を満たし、単発のユン・チェウォン欠けは1件許容で通過する
        val dict = NovelDict(characters = mapOf("진예서" to "ジン・イェソ", "윤채원" to "ユン・チェウォン"))
        val ctx = looseCtx(call = { _, _, _ ->
            ok("イェソが尋ねた。")
        }).copy(dictionary = dict, sourceLang = SourceLang.KO)
        val r = translateSingle("진예서가 물었다. 윤채원이 만들었다.", ctx)
        assertTrue(r is SingleResult.Translated)
        assertTrue((r as SingleResult.Translated).text.contains("イェソ"))
    }
}

/**
 * 実機プロバイダの2つの振る舞いを再現するfake:
 * 一覧の古さで指定名を見落とす＋同名生成を "(1)" に自動リネームする。
 */
internal class StaleRenameStore(
    val inner: InMemoryFileStore = InMemoryFileStore()
) : FileStore by inner {
    var staleFails = 0
    var staleMatcher: (String) -> Boolean = { it.endsWith(".failed") }

    override suspend fun findChild(dirUri: String, name: String): VDoc? {
        if (staleMatcher(name) && staleFails > 0) {
            staleFails--
            return null
        }
        return inner.findChild(dirUri, name)
    }

    override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? {
        if (inner.findChild(dirUri, name) != null) {
            return inner.createFile(dirUri, "$name (1)", mime)
        }
        return inner.createFile(dirUri, name, mime)
    }

    suspend fun childNames(dirUri: String): List<String> = inner.children(dirUri).map { it.name }
}
