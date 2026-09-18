package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.AcquireResult
import com.example.novelscraper.translation.v2.domain.ConfigKind
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.GeminiErrorMapper
import com.example.novelscraper.translation.v2.domain.GenericErrorMapper
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.SamplingParam
import com.example.novelscraper.translation.v2.domain.ThinkingSupport
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.isDeterministic
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.domain.isTerminal
import com.example.novelscraper.translation.v2.domain.resolveDouble
import com.example.novelscraper.translation.v2.domain.resolveInt
import com.example.novelscraper.translation.v2.domain.resolveOpenRouterParams
import com.example.novelscraper.translation.v2.domain.resolveOption
import com.example.novelscraper.translation.v2.domain.resolveProviderOrder
import com.example.novelscraper.translation.v2.domain.resolveProviderOrderReport
import com.example.novelscraper.translation.v2.domain.resolveReasoningEffort
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.infra.buildGeminiBody
import com.example.novelscraper.translation.v2.infra.buildOpenRouterBody
import com.example.novelscraper.translation.v2.infra.parseGeminiResponse
import com.example.novelscraper.translation.v2.infra.parseGeminiRetryDelay
import com.example.novelscraper.translation.v2.infra.parseOpenRouterResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test

class V2DomainTest {

    @Test
    fun testFailureMapper_FixtureTable() {
        val kinds = FailureKind.entries.associateBy { it.name }
        val configs = ConfigKind.entries.associateBy { it.name }
        data class Row(val code: Int, val body: String, val kind: String, val config: String?)

        val rows = listOf(
            Row(403, "", "CONFIG", "AUTH_FAILED"),
            Row(410, "", "CONFIG", "MODEL_NOT_FOUND"),
            Row(500, "", "RETRYABLE_AFTER", null),
            Row(503, "overloaded", "RETRYABLE_AFTER", null),
            Row(400, "The model `x` does not exist", "CONFIG", "MODEL_NOT_FOUND"),
            Row(400, "Incorrect API key provided", "CONFIG", "AUTH_FAILED"),
            Row(400, "Free tier is not available in your country", "CONFIG", "PAYMENT_REQUIRED"),
            Row(400, "Invalid parameter: temperature must be between 0 and 2", "CONFIG", "INVALID_PARAM"),
            Row(400, "Unsupported parameter: reasoning.effort", "CONFIG", "INVALID_PARAM"),
            Row(400, "This model's maximum context length is 8192 tokens", "FATAL", null),
            Row(400, "", "FATAL", null),
            Row(408, "", "RETRYABLE_AFTER", null),
            Row(425, "", "RETRYABLE_AFTER", null),
            Row(429, "too many requests", "QUOTA_MINUTE", null),
            Row(418, "", "FATAL", null)
        )
        for ((code, body, kind, config) in rows) {
            val r = GenericErrorMapper.map(code, body)
            assertEquals("code=$code", kinds.getValue(kind), r.kind)
            if (config != null) assertEquals("code=$code", configs.getValue(config), r.configKind)
        }
    }

    @Test
    fun testGeminiMapper_DailySplit() {
        assertEquals(
            FailureKind.QUOTA_DAILY,
            GeminiErrorMapper.map(429, "GenerateRequestsPerDayPerProjectPerModel-FreeTier").kind
        )
        assertEquals(FailureKind.QUOTA_MINUTE, GeminiErrorMapper.map(429, "GenerateRequestsPerMinutePerProjectPerModel").kind)
        assertEquals(FailureKind.QUOTA_MINUTE, GeminiErrorMapper.map(429, "").kind)
        assertTrue(GeminiErrorMapper.isDailyQuotaExceeded("per day quota exceeded"))
        assertFalse(GeminiErrorMapper.isDailyQuotaExceeded("per minute quota exceeded"))
    }

    @Test
    fun testGeminiRetryDelay_Parsing() {
        // Google RPC RetryInfo (retryDelay: "34.460462s" -> 切り上げ35秒)
        val bodyWithFraction = """
            {
              "error": {
                "code": 429,
                "message": "Resource has been exhausted",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "34.460462s"
                  }
                ]
              }
            }
        """.trimIndent()
        assertEquals(35L, parseGeminiRetryDelay(bodyWithFraction))

        val bodyWithInteger = """{"error": {"details": [{"retryDelay": "15s"}]}}"""
        assertEquals(15L, parseGeminiRetryDelay(bodyWithInteger))

        assertNull(parseGeminiRetryDelay("""{"error": "no details"}"""))

        // parseGeminiResponse 経由で retryAfterSec に反映されること
        val res = parseGeminiResponse(429, bodyWithFraction)
        assertTrue(res is LlmResult.Failure)
        val failure = (res as LlmResult.Failure).failure
        assertEquals(FailureKind.QUOTA_MINUTE, failure.kind)
        assertEquals(35, failure.retryAfterSec)
    }

    @Test
    fun testCapabilities_Lookup() {
        // 3.8はLOW/MEDIUM/HIGHのみ
        val flash38 = GEMINI_DESCRIPTOR.capabilitiesFor("gemini-3.8-flash").thinking
        assertTrue(flash38 is ThinkingSupport.Levels)
        assertEquals(setOf("low", "medium", "high"), (flash38 as ThinkingSupport.Levels).supported)

        // preview既定high・4値対応
        val preview = GEMINI_DESCRIPTOR.capabilitiesFor("gemini-3-flash-preview").thinking
        assertTrue(preview is ThinkingSupport.Levels)
        assertEquals("high", (preview as ThinkingSupport.Levels).default)

        // Gemma系は思考なし（None）として収録（送らない・出さない）
        assertEquals(ThinkingSupport.None, GEMINI_DESCRIPTOR.capabilitiesFor("gemma-4-31b-it").thinking)
        // 大文字・空白つきも正規化
        assertEquals(ThinkingSupport.None, GEMINI_DESCRIPTOR.capabilitiesFor("  GEMMA-3-27B-IT ").thinking)
        // OpenRouter未知IDは控えめ既定（思考なし・温度のみ）
        val unknown = OPENROUTER_DESCRIPTOR.capabilitiesFor("some/new-model")
        assertEquals(ThinkingSupport.None, unknown.thinking)
        assertNotNull(unknown.sampling["temperature"])
        // 枠帰属：Geminiはモデル別、OpenRouterは共有
        assertEquals("gemini-3.5-flash", GEMINI_DESCRIPTOR.quotaScopeOf("gemini-3.5-flash"))
        assertEquals("shared", OPENROUTER_DESCRIPTOR.quotaScopeOf("anything"))
    }

    @Test
    fun testParams_Resolution() {
        // 選択肢：上書き優先→既定→空、非対応は落とす
        assertEquals(null, resolveOption(setOf("a"), null, null))
        assertEquals("b", resolveOption(setOf("a", "b"), "a", "b"))
        assertEquals("a", resolveOption(setOf("a", "b"), "a", null))
        assertEquals(null, resolveOption(setOf("a"), "a", "zzz"))
        assertEquals("a", resolveOption(null, "a", null))
        // 数値：上書き優先、範囲丸め
        val range = SamplingParam(0.0, 1.0)
        assertEquals(0.5, resolveDouble(range, 0.5, null).value)
        val over = resolveDouble(range, null, 2.0)
        assertEquals(1.0, over.value)
        assertTrue(over.coerced)
        assertEquals(null, resolveDouble(range, null, null).value)
        val intOver = resolveInt(1..10, null, 99)
        assertEquals(10, intOver.value)
        assertTrue(intOver.coerced)
    }

    @Test
    fun testQuotaPool_Semantics() = kotlinx.coroutines.runBlocking {
        val pool = QuotaPool(listOf("k1", "k2"))
        // 初期確保
        val first = pool.claimNew(listOf("m-a"))
        assertNotNull(first)
        // 一時冷却は枯渇にしない
        pool.reportQuota(0, "m-a", daily = false, cooldownSec = 60)
        assertFalse(pool.isExhausted(listOf("m-a")))
        // 全資格情報×対象スコープ枯渇で真
        pool.reportQuota(0, "m-a", daily = true, cooldownSec = 15)
        assertFalse(pool.isExhausted(listOf("m-a")))
        pool.reportQuota(1, "m-a", daily = true, cooldownSec = 15)
        assertTrue(pool.isExhausted(listOf("m-a")))
        // 他スコープは継続可
        assertFalse(pool.isExhausted(listOf("m-b")))
        assertTrue(pool.claimNew(listOf("m-b")) != null)
        assertTrue(pool.isScopeDead(0, "m-a"))
        assertFalse(pool.isScopeDead(0, "m-b"))
        // 空資格情報は枯渇
        val empty = QuotaPool(emptyList())
        assertTrue(empty.isExhausted(listOf("m-a")))
        val acq = empty.acquire(listOf("m-a"))
        assertTrue(acq is AcquireResult.Exhausted)
        // リセットで復活
        pool.reset()
        assertFalse(pool.isExhausted(listOf("m-a")))
    }

    @Test
    fun testCostMeter_Guard() {        val meter = CostMeter(maxTokens = 100, maxCost = 1.0)
        assertTrue(meter.add(tokens = 60, cost = 0.4))
        assertTrue(meter.add(tokens = 40, cost = 0.5))
        // 上限超過は拒否し、計数は進めない
        assertFalse(meter.add(tokens = 1))
        assertFalse(meter.add(cost = 0.2))
        assertEquals(100L to 0.9, meter.snapshot())
        // 上限なしは常に許可
        val free = CostMeter()
        assertTrue(free.add(tokens = Long.MAX_VALUE / 2))
    }

    @Test
    fun testInMemoryFileStore_CRUD() = kotlinx.coroutines.runBlocking {
        val store = com.example.novelscraper.translation.v2.infra.InMemoryFileStore()
        val root = store.createRoot("novel")
        assertEquals(emptyList<String>(), store.children("mem://missing").map { it.name })

        val sub = store.createDir(root.uri, "parts")
        assertNotNull(sub)
        val file = store.createFile(sub!!.uri, "a.txt", "text/plain")
        assertNotNull(file)
        assertTrue(store.writeText(file!!.uri, "hello"))
        assertEquals("hello", store.readText(file.uri))
        assertEquals(listOf("a.txt"), store.children(sub.uri).map { it.name })
        assertEquals("a.txt", store.findChild(sub.uri, "a.txt")?.name)
        assertNull(store.findChild(sub.uri, "b.txt"))
        // ディレクトリへの書込・ファイル扱いは拒否
        assertFalse(store.writeText(sub.uri, "x"))
        assertNull(store.readText(sub.uri))
        assertFalse(store.deleteFile(sub.uri))
        // 削除
        assertTrue(store.deleteFile(file.uri))
        assertNull(store.readText(file.uri))
        assertTrue(store.deleteRecursively(sub.uri))
        assertEquals(emptyList<String>(), store.children(root.uri).map { it.name })
    }

    @Test
    fun testHandlerBuilders_OmitNulls() {
        val req = com.example.novelscraper.translation.v2.domain.LlmRequest(
            providerId = "gemini",
            model = "gemini-3.5-flash",
            systemPrompt = "sys",
            userText = "hello",
            options = com.example.novelscraper.translation.v2.domain.RequestOptions(
                thinkingLevel = "low"
            )
        )
        val geminiBody = buildGeminiBody(req)
        assertTrue(geminiBody.contains("thinkingLevel"))
        assertTrue(!geminiBody.contains("temperature"))
        assertTrue(!geminiBody.contains("thinkingBudget"))

        val orBody = buildOpenRouterBody(req)
        assertTrue(orBody.contains("gemini-3.5-flash"))
        assertTrue(!orBody.contains("reasoning"))
        val orReasoning = buildOpenRouterBody(
            req, reasoningEffort = "high"
        )
        assertTrue(orReasoning.contains("\"effort\":\"high\""))
    }

    @Test
    fun testHandlerParsers_Blocks() {
        // 思考パート除外＋本文結合
        val ok = parseGeminiResponse(
            200,
            """{"candidates": [{"content": {"parts": [
              {"text": "考え", "thought": true},
              {"text": "本文"}
            ]}, "finishReason": "STOP"}],
            "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}}"""
        )
        assertTrue(ok is com.example.novelscraper.translation.v2.domain.LlmResult.Success)
        assertEquals("本文", (ok as com.example.novelscraper.translation.v2.domain.LlmResult.Success).text)
        assertEquals(10, ok.promptTokens)
        // プロンプト拒否は確定失敗
        val blocked = parseGeminiResponse(200, """{"promptFeedback": {"blockReason": "SAFETY"}}""")
        assertTrue(blocked is com.example.novelscraper.translation.v2.domain.LlmResult.Failure)
        val bf = (blocked as com.example.novelscraper.translation.v2.domain.LlmResult.Failure).failure
        assertEquals(
            com.example.novelscraper.translation.v2.domain.FailureKind.BLOCKED_DETERMINISTIC,
            bf.kind
        )
        // 429日次分離
        val daily = parseGeminiResponse(429, "GenerateRequestsPerDayPerProjectPerModel")
        val df = (daily as com.example.novelscraper.translation.v2.domain.LlmResult.Failure).failure
        assertEquals(com.example.novelscraper.translation.v2.domain.FailureKind.QUOTA_DAILY, df.kind)

        val orOk = parseOpenRouterResponse(
            200,
            """{"choices": [{"message": {"content": "訳文"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 3, "completion_tokens": 7}}"""
        )
        assertTrue(orOk is com.example.novelscraper.translation.v2.domain.LlmResult.Success)
        assertEquals(7, (orOk as com.example.novelscraper.translation.v2.domain.LlmResult.Success).completionTokens)
        val orCut = parseOpenRouterResponse(200, """{"choices": [{"message": {}, "finish_reason": "length"}]}""")
        val cf = (orCut as com.example.novelscraper.translation.v2.domain.LlmResult.Failure).failure
        assertEquals(com.example.novelscraper.translation.v2.domain.FailureKind.FATAL, cf.kind)
    }

    @Test
    fun testHandlerParsers_TruncationWithTextIsFailure() {
        // 非空でも打切り信号があれば未完扱い（切れ端の完成誤認を防ぐ）。保持扱いのため決定性は偽
        val orTrunc = parseOpenRouterResponse(
            200,
            """{"choices": [{"message": {"content": "途中までの訳文"}, "finish_reason": "length"}]}"""
        )
        assertTrue(orTrunc is com.example.novelscraper.translation.v2.domain.LlmResult.Failure)
        val orF = (orTrunc as com.example.novelscraper.translation.v2.domain.LlmResult.Failure).failure
        assertEquals("cutoff:length", orF.note)
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(orF.kind, orF.note))
        // 生値のみの場合も検出する
        val orNative = parseOpenRouterResponse(
            200,
            """{"choices": [{"message": {"content": "途中までの訳文"}, "finish_reason": "stop", "native_finish_reason": "LENGTH"}]}"""
        )
        assertTrue(orNative is com.example.novelscraper.translation.v2.domain.LlmResult.Failure)

        val geminiTrunc = parseGeminiResponse(
            200,
            """{"candidates": [{"content": {"parts": [{"text": "途中までの訳文"}]}, "finishReason": "MAX_TOKENS"}]}"""
        )
        assertTrue(geminiTrunc is com.example.novelscraper.translation.v2.domain.LlmResult.Failure)
        val gF = (geminiTrunc as com.example.novelscraper.translation.v2.domain.LlmResult.Failure).failure
        assertEquals("cutoff:max-tokens", gF.note)
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(gF.kind, gF.note))
    }

    @Test
    fun testOpenRouterParams_Gating() {
        // reasoningEffort: 表外値・空白は落とす。大文字・前後空白は正規化する。noneは公式指定値として通す
        assertEquals(null, resolveReasoningEffort(null))
        assertEquals(null, resolveReasoningEffort(""))
        assertEquals("none", resolveReasoningEffort("none"))
        assertEquals(null, resolveReasoningEffort("ultra"))
        assertEquals("high", resolveReasoningEffort("high"))
        assertEquals("high", resolveReasoningEffort(" HIGH "))

        // providerOrder: 空要素除去・重複除去・上限打切り
        assertEquals(emptyList<String>(), resolveProviderOrder(emptyList()))
        assertEquals(listOf("a", "b"), resolveProviderOrder(listOf(" a ", "", "b", "a")))
        assertEquals(10, resolveProviderOrder((1..20).map { "p$it" }).size)
        assertEquals(emptyList<String>(), resolveProviderOrder(listOf("x".repeat(65))))

        // allow_fallbacksはorder空時に落とす
        val dropped = resolveOpenRouterParams("high", null, emptyList(), true)
        assertEquals(emptyList<String>(), dropped.providerOrder)
        assertEquals(null, dropped.providerAllowFallbacks)
        val kept = resolveOpenRouterParams("high", null, listOf("a"), true)
        assertEquals(listOf("a"), kept.providerOrder)
        assertEquals(true, kept.providerAllowFallbacks)
    }

    @Test
    fun testOpenRouterBody_Gating() {
        val req = com.example.novelscraper.translation.v2.domain.LlmRequest(
            providerId = "openrouter",
            model = "x/y",
            systemPrompt = "sys",
            userText = "hello"
        )
        // 表外effortはreasoningごと落ちる（400誤爆を防ぐ）
        val invalid = buildOpenRouterBody(req, reasoningEffort = "ultra")
        assertTrue(!invalid.contains("reasoning"))
        // 大文字は正規化されて送られる
        val normalized = buildOpenRouterBody(req, reasoningEffort = " HIGH ")
        assertTrue(normalized.contains("\"effort\":\"high\""))
        // enabledとeffortは両立して送られる（公式仕様準拠）
        val both = buildOpenRouterBody(req, reasoningEffort = "high", reasoningEnabled = true)
        assertTrue(both.contains("\"enabled\":true"))
        assertTrue(both.contains("\"effort\":\"high\""))
        // orderの空要素・重複は除去される
        val order = buildOpenRouterBody(req, providerOrder = listOf(" b ", "", "b", "a"))
        assertTrue(order.contains("\"order\":[\"b\",\"a\"]"))
        // order空のallow_fallbacks単独指定は送出しない
        val alone = buildOpenRouterBody(req, providerOrder = emptyList(), providerAllowFallbacks = true)
        assertTrue(!alone.contains("allow_fallbacks"))
    }

    @Test
    fun testCleanCode_GuardsPredicatesAndProviderId() {
        // 非有限数は未指定に落とす（送るとJSON化で必ず失敗するため）
        val nan = resolveDouble(SamplingParam(0.0, 1.0), null, Double.NaN)
        assertEquals(null, nan.value)
        assertTrue(nan.coerced)
        val inf = resolveDouble(SamplingParam(0.0, 1.0), Double.POSITIVE_INFINITY, null)
        assertEquals(null, inf.value)
        // 述語の分组は単一真実
        assertTrue(FailureKind.BLOCKED_DETERMINISTIC.isDeterministic())
        assertTrue(FailureKind.CONFIG.isDeterministic())
        assertFalse(FailureKind.FATAL.isDeterministic())
        assertTrue(FailureKind.QUOTA_MINUTE.isQuotaLike())
        assertTrue(FailureKind.RETRYABLE_AFTER.isQuotaLike())
        assertFalse(FailureKind.FATAL.isQuotaLike())
        assertTrue(FailureKind.FATAL.isTerminal())
        assertFalse(FailureKind.QUOTA_DAILY.isTerminal())
        // プロバイダー識別子のtypo耐性
        assertEquals(ProviderId.GEMINI, ProviderId.parse(" gemini "))
        assertEquals(ProviderId.OPENROUTER, "OpenRouter".toProviderId())
        assertEquals(null, ProviderId.parse("groq"))
        assertEquals(null, ProviderId.parse(null))
        // 解決報告の内訳
        val report = resolveProviderOrderReport(listOf("b", "", "b", "x".repeat(65)))
        assertEquals(listOf("b"), report.resolved)
        assertTrue(report.droppedBlanks)
        assertTrue(report.droppedDupes)
        assertTrue(report.droppedLong)
        assertFalse(report.truncated)
    }

    @Test
    fun testSendGate_Serializes() = kotlinx.coroutines.runBlocking {
        val gate = com.example.novelscraper.translation.v2.domain.V2SendGate()
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val start = System.currentTimeMillis()
        val jobs = listOf(1, 2, 3).map {
            testScope.async {
                gate.acquire(100)
            }
        }
        jobs.awaitAll()
        // 3 serialized slots with 100ms interval take at least 200ms.
        assertTrue(System.currentTimeMillis() - start >= 150)
    }

    @Test
    fun testSendGate_NoWaitWhenIdle() = kotlinx.coroutines.runBlocking {
        var now = 1_000_000L
        val gate = com.example.novelscraper.translation.v2.domain.V2SendGate(clockMs = { now })
        gate.acquire(10_000)
        now += 20_000
        val start = System.currentTimeMillis()
        gate.acquire(10_000)
        // Interval already elapsed: returns without real delay.
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }
}
