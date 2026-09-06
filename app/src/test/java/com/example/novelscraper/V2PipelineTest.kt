package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.pipeline.Attempt
import com.example.novelscraper.translation.v2.pipeline.AttemptOptions
import com.example.novelscraper.translation.v2.pipeline.BatchOutcome
import com.example.novelscraper.translation.v2.pipeline.COMPLETION_MARKER
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.DriverOutcome
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.Route
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
import com.example.novelscraper.translation.v2.pipeline.hasBatchClosedTags
import com.example.novelscraper.translation.v2.pipeline.joinOutputsStreaming
import com.example.novelscraper.translation.v2.pipeline.kanaRate
import com.example.novelscraper.translation.v2.pipeline.matchDictionaryEntries
import com.example.novelscraper.translation.v2.pipeline.meetsKanaFloor
import com.example.novelscraper.translation.v2.pipeline.mergeDecision
import com.example.novelscraper.translation.v2.pipeline.parseBatchResponse
import com.example.novelscraper.translation.v2.pipeline.parseNovelDict
import com.example.novelscraper.translation.v2.pipeline.routeFor
import com.example.novelscraper.translation.v2.pipeline.selectSampleFiles
import com.example.novelscraper.translation.v2.pipeline.sha256Hex
import com.example.novelscraper.translation.v2.pipeline.sizeRatioOk
import com.example.novelscraper.translation.v2.pipeline.splitIntoChunks
import com.example.novelscraper.translation.v2.pipeline.stripFences
import com.example.novelscraper.translation.v2.pipeline.translateBatch
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.translateSingle
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
    fun testBatchIO_Roundtrip() {
        val input = buildBatchInput(listOf("a.txt" to "你好", "b.txt" to "早上好"))
        assertTrue(input.contains("<doc id=\"1\">"))
        val resp = "<translations>\n<trans id=\"1\">\nこんにちは\n</trans>\n<trans id=\"2\">\nおはよう\n</trans>\n</translations>"
        assertTrue(hasBatchClosedTags(resp))
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
        // bだけ確定的失敗 → 部分マージで確定（2バッチ化のため上限を小さく）
        val dict = generateDictionary(
            store, root.uri, files,
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
            store, root2.uri, files,
            call = { _, _, _ -> fail(FailureKind.QUOTA_MINUTE) },
            DictOptions(maxRetriesPerBatch = 0, parallelism = 2)
        )
        assertNull(held)
    }

    @Test
    fun testDictStage_HashMismatchRegen() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("d")
        val goodJson = """{"style":"カタカナ","characters":{"山田":"ヤマダ"},"genders":{}}"""
        val files = listOf("a.txt" to "山田の物語")
        val batchPrompt = DictOptions().prompts.batch
        var batchCalls = 0
        val countingCall: suspend (String, String, String) -> LlmResult = { _, prompt, _ ->
            if (prompt == batchPrompt) batchCalls++
            ok(goodJson)
        }
        val first = generateDictionary(
            store, root.uri, files,
            call = countingCall,
            DictOptions(maxRetriesPerBatch = 0)
        )
        assertNotNull(first)
        assertEquals(1, batchCalls)
        // 内容変更なし → 抽出はキャッシュ再利用（レビューは毎回走る）
        val second = generateDictionary(
            store, root.uri, files,
            call = countingCall,
            DictOptions(maxRetriesPerBatch = 0)
        )
        assertNotNull(second)
        assertEquals(1, batchCalls)
        // 内容変更 → ハッシュ不一致で抽出から再取得
        val third = generateDictionary(
            store, root.uri, listOf("a.txt" to "山田の物語・改訂版"),
            call = countingCall,
            DictOptions(maxRetriesPerBatch = 0)
        )
        assertNotNull(third)
        assertEquals(2, batchCalls)
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
    fun testRouteFor() {
        assertEquals(Route.SINGLE, routeFor(0, 100))
        assertEquals(Route.BATCHABLE, routeFor(50, 100))
        assertEquals(Route.LARGE, routeFor(101, 100))
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
}
