package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.Attempt
import com.example.novelscraper.translation.v2.pipeline.AttemptOptions
import com.example.novelscraper.translation.v2.pipeline.BatchOutcome
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
import com.example.novelscraper.translation.v2.pipeline.matchDictionaryEntries
import com.example.novelscraper.translation.v2.pipeline.meetsKanaFloor
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.pipeline.mergeDecision
import com.example.novelscraper.translation.v2.pipeline.parseBatchResponse
import com.example.novelscraper.translation.v2.pipeline.parseBatchJsonResponse
import com.example.novelscraper.translation.v2.pipeline.parseNovelDict
import com.example.novelscraper.translation.v2.pipeline.sanitizeNovelDict
import com.example.novelscraper.translation.v2.pipeline.selectSampleFiles
import com.example.novelscraper.translation.v2.pipeline.sha256Hex
import com.example.novelscraper.translation.v2.pipeline.sizeRatioOk
import com.example.novelscraper.translation.v2.pipeline.splitIntoChunks
import com.example.novelscraper.translation.v2.pipeline.stripFences
import com.example.novelscraper.translation.v2.pipeline.translateBatch
import com.example.novelscraper.translation.v2.pipeline.writeFailed
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.translateSingle
import com.example.novelscraper.translation.v2.pipeline.ResidualOptions
import com.example.novelscraper.translation.v2.pipeline.verifyRejectReason
import com.example.novelscraper.translation.v2.pipeline.verifyTranslation
import com.example.novelscraper.translation.v2.pipeline.LargeOptions
import org.junit.Assert.*
import org.junit.Test

class V2PipelineTest {

    private fun ok(text: String) = LlmResult.Success(text = text)
    private fun fail(kind: FailureKind) =
        LlmResult.Failure(ClassifiedFailure(kind))

    private fun looseCtx(
        call: suspend (String, String, String) -> LlmResult,
        drivers: List<String> = listOf("d1")
    ) = TranslateContext(
        basePrompts = mapOf(1 to "base"),
        promptOrder = listOf(1),
        driverNames = drivers,
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
        // マーカーが末尾300字より手前（途絶）→ 救済せずnull
        assertNull(checkAndStripMarker("本文です。[SRC_END]" + "あ".repeat(350), true))
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
    fun testTranslateSingle_Flow() = kotlinx.coroutines.runBlocking {
        val ctx = looseCtx(call = { _, _, source -> ok("訳文:$source") })
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
        assertTrue(okResult)
        val final = store.readText(store.findChild(root.uri, "f.txt")!!.uri)!!
        assertTrue(final.contains("訳1"))
        assertTrue(final.contains("訳$n"))
        // 作業所は掃除される
        assertNull(store.findChild(root.uri, ".parts_f"))
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
        // 日本語見出しでない値は落とす（韓国語のままの採用を防ぐ）。性別表も連動して刈る。
        val dict = NovelDict(
            style = "カタカナ",
            characters = mapOf("山田" to "ヤマダ", "김민준" to "김민준", "John" to "ジョン", "李云" to "李雲"),
            genders = mapOf("山田" to "男", "김민준" to "男", "John" to "不明")
        )
        val cleaned = sanitizeNovelDict(dict)
        assertEquals(mapOf("山田" to "ヤマダ", "John" to "ジョン", "李云" to "李雲"), cleaned.characters)
        // 性別表は生存項目に連動（「不明」は使用時に除外されるため保持でよい）
        assertEquals(mapOf("山田" to "男", "John" to "不明"), cleaned.genders)
        assertNull(cleaned.characters["김민준"])
        assertNull(cleaned.genders["김민준"])
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
    fun testDictExamples_Fallback() = kotlinx.coroutines.runBlocking {
        // 完全一致ゼロ時は参考例が指示に入る（表記揺れでも表記パターンを伝える）
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
        assertTrue(prompts[0].contains("参考例"))
        assertTrue(prompts[0].contains("ヤマダ"))
    }

    @Test
    fun testDictParse_NestedFallback() {
        // 入れ子形式 {"名前": {"name": "読み"}} も救済する（旧版の柔軟パーサー復活）
        val nested = """{"style":"カタカナ","characters":{"김민준":{"name":"金","gender":"男"}}}"""
        val parsed = parseNovelDict(nested)
        assertNotNull(parsed)
        assertEquals("金", parsed!!.characters["김민준"])
        assertEquals("男", parsed.genders["김민준"])
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
        val entries = matchDictionaryEntries("山田と田中", mapOf("山田" to "ヤマダ", "佐藤" to "サトウ"))
        assertEquals(1, entries.size)
        val prompt = buildSystemPrompt("base", previousTranslatedTail = "prev", dictionaryEntries = entries)
        assertTrue(prompt.contains("PREVIOUS CONTEXT"))
        assertTrue(prompt.contains(COMPLETION_MARKER))
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
        assertTrue(done)
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
    fun testBatch_UnclosedTailSegmentRescued() {
        val cutText = """
            <trans id="1">第一話の訳文です。</trans>
            <trans id="2">第二話の途中まで生成された訳文
        """.trimIndent()
        val parsed = parseBatchResponse(cutText)
        assertNotNull(parsed)
        assertEquals("第一話の訳文です。", parsed!![1])
        assertEquals("第二話の途中まで生成された訳文", parsed[2])
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
