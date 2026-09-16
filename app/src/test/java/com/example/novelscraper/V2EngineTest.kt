package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.engine.EngineOptions
import com.example.novelscraper.translation.v2.engine.patientSleep
import com.example.novelscraper.translation.v2.engine.LangCacheStore
import com.example.novelscraper.translation.v2.engine.DictionaryBuilder
import com.example.novelscraper.translation.v2.engine.Rotation
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.engine.UnmanagedRotation
import com.example.novelscraper.translation.v2.engine.resolveProfileOptions
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.settings.V2DictSettings
import com.example.novelscraper.translation.v2.settings.V2Limits
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PrevContext
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SplitSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class V2EngineTest {

    private fun scriptedHandler(script: Map<String, MutableList<LlmResult>>): ProviderHandler {
        return object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val queue = script[request.model]
                    ?: return LlmResult.Success("応答:" + request.userText.take(10))
                return if (queue.isNotEmpty()) queue.removeAt(0)
                else LlmResult.Success("応答:" + request.userText.take(10))
            }
        }
    }

    private fun quotaDaily() = LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_DAILY))
    private fun quotaMinute() = LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_MINUTE))

    private fun rotationOf(
        profiles: List<V2ModelProfile>,
        pool: QuotaPool,
        script: Map<String, MutableList<LlmResult>>,
        keyIndex: Int = 0,
        key: String = "k1"
    ): Rotation {
        val handler = scriptedHandler(script)
        return Rotation(
            workerId = 1,
            profiles = profiles,
            pool = pool,
            keyIndex = keyIndex,
            key = key,
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR, ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "or",
            maxSameRetries = 0,
            log = {}
        )
    }

    private fun geminiProfile(model: String) = V2ModelProfile(providerId = "gemini", model = model)

    @Test
    fun testRotation_SendGateFlow() = kotlinx.coroutines.runBlocking {
        // Gate with zero interval never blocks the flow.
        val gate = com.example.novelscraper.translation.v2.domain.V2SendGate()
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                return LlmResult.Success("ok")
            }
        }
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(geminiProfile("gemini-3.5-flash")),
            pool = QuotaPool(listOf("k1")),
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            maxSameRetries = 0,
            sendGate = gate,
            sendGateIntervalMs = 0,
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        assertFalse(rotation.exhausted)
    }

    @Test
    fun testRotation_ModelFallback() = kotlinx.coroutines.runBlocking {
        val pool = QuotaPool(listOf("k1", "k2"))
        val rotation = rotationOf(
            listOf(geminiProfile("gemini-3.5-flash"), geminiProfile("gemini-3.6-flash")),
            pool,
            mapOf("gemini-3.5-flash" to mutableListOf(quotaMinute()))
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        assertFalse(rotation.exhausted)
    }

    @Test
    fun testRotation_PerProfilePrompts() = kotlinx.coroutines.runBlocking {
        val pool = QuotaPool(listOf("k1"))
        val p1 = geminiProfile("model-main").copy(id = "p1")
        val p2 = geminiProfile("model-sub").copy(id = "p2")
        val seenPrompts = mutableListOf<String>()

        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                seenPrompts.add("${request.model}:${request.systemPrompt}")
                return if (request.model == "model-main") {
                    // メインモデルは一時失敗させてフォールバックを促す
                    LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER))
                } else {
                    LlmResult.Success("ok from sub")
                }
            }
        }
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(p1, p2),
            pool = pool,
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            maxSameRetries = 0,
            log = {}
        )
        val profilePrompts = mapOf(
            "p1" to listOf("main-prompt-1"),
            "p2" to listOf("sub-prompt-7")
        )
        val result = rotation.execute(
            prompts = listOf("fallback-default"),
            source = "src",
            profilePrompts = profilePrompts
        )
        assertTrue(result is LlmResult.Success)
        assertEquals(listOf("model-main:main-prompt-1", "model-sub:sub-prompt-7"), seenPrompts)
    }

    @Test
    fun testRotation_PairSkipAndExhaust() = kotlinx.coroutines.runBlocking {
        val pool = QuotaPool(listOf("k1"))
        pool.reportQuota(0, "gemini-3.5-flash", daily = true, cooldownSec = 15)
        val seen = mutableListOf<String>()
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                seen.add(request.model)
                return LlmResult.Success("ok")
            }
        }
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(geminiProfile("gemini-3.5-flash"), geminiProfile("gemini-3.6-flash")),
            pool = pool,
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            maxSameRetries = 0,
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        // 枯渇ペアは飛ばして無駄打ちしない
        assertEquals(listOf("gemini-3.6-flash"), seen)

        // 全ペア枯渇で終了
        pool.reportQuota(0, "gemini-3.6-flash", daily = true, cooldownSec = 15)
        val rotation2 = rotationOf(
            listOf(geminiProfile("gemini-3.5-flash")),
            pool,
            mapOf("gemini-3.5-flash" to mutableListOf(quotaDaily()))
        )
        val result2 = rotation2.execute(listOf("prompt"), "src")
        assertTrue(result2 is LlmResult.Failure)
        assertTrue(rotation2.exhausted)
    }

    @Test
    fun testRotation_OpenRouterOnlyQuotaRecoversWithoutPool() = kotlinx.coroutines.runBlocking {
        // 非管理のみ構成はプールを持たず、同一キー待機再送で復帰する（S-2の構造的防止）
        val script: Map<String, MutableList<LlmResult>> = mapOf("x/y" to mutableListOf(quotaMinute()))
        val handler = scriptedHandler(script)
        val rotation = UnmanagedRotation(
            workerId = 1,
            profiles = listOf(V2ModelProfile(providerId = "openrouter", model = "x/y")),
            key = "or",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR, ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            cooldownSec = 0,
            maxSameRetries = 0,
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        assertFalse(rotation.exhausted)
    }

    @Test
    fun testRotation_CostCapAbortsEpoch() = kotlinx.coroutines.runBlocking {
        // コスト上限到達はその場で確定し、残り指示文への無駄打ちをしないこと
        var calls = 0
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                calls++
                return LlmResult.Success("text", promptTokens = 3, completionTokens = 4)
            }
        }
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(
                V2ModelProfile(providerId = "gemini", model = "m1"),
                V2ModelProfile(providerId = "gemini", model = "m2")
            ),
            pool = QuotaPool(listOf("k1")),
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            maxSameRetries = 1,
            meter = CostMeter(maxTokens = 5),
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Failure)
        assertEquals("cost-cap", (result as LlmResult.Failure).failure.note)
        assertEquals(1, calls)
    }

    @Test
    fun testRotation_QuotaWaitIsLogged() = kotlinx.coroutines.runBlocking {
        // 待機に入る前に理由と秒数をログに出す（無言の停止に見せない）
        val script: Map<String, MutableList<LlmResult>> = mapOf("m" to mutableListOf(quotaMinute()))
        val handler = scriptedHandler(script)
        val waits = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "m")),
            pool = QuotaPool(listOf("k1")),
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            geminiCooldownSec = 5,
            maxSameRetries = 1,
            sleeper = { waits.add(it) },
            log = { synchronized(logs) { logs.add(it) } }
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        assertEquals(5000L, waits.sum())
        assertTrue(waits.all { it in 1..5000L })
        assertTrue(logs.any { it.contains("待機") && it.contains("再送") })
    }

    @Test
    fun testRotation_RetryAfterSecHonored() = kotlinx.coroutines.runBlocking {
        // APIから指定されたRetry-Afterがある場合、設定値（60秒）ではなく指定秒数（12秒）で待機する
        val failureWithRetryAfter = LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_MINUTE, retryAfterSec = 12))
        val script: Map<String, MutableList<LlmResult>> = mapOf("m" to mutableListOf(failureWithRetryAfter))
        val handler = scriptedHandler(script)
        val waits = mutableListOf<Long>()
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "m")),
            pool = QuotaPool(listOf("k1")),
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            geminiCooldownSec = 60,
            maxSameRetries = 1,
            sleeper = { waits.add(it) },
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        assertEquals(12000L, waits.sum())
        assertTrue(waits.all { it in 1..12000L })
    }

    @Test
    fun testRotation_TransientErrorUsesTransientDelay() = kotlinx.coroutines.runBlocking {
        // 一時エラー(RETRYABLE_AFTER)発生時は geminiCooldownSec(60秒) ではなく transientRetryDelaySec(3秒) で待機する
        val failureTransient = LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER))
        val script: Map<String, MutableList<LlmResult>> = mapOf("m" to mutableListOf(failureTransient))
        val handler = scriptedHandler(script)
        val waits = mutableListOf<Long>()
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "m")),
            pool = QuotaPool(listOf("k1")),
            keyIndex = 0,
            key = "k1",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR),
            handlerFactory = { _, _ -> handler },
            openRouterKey = "",
            geminiCooldownSec = 60,
            transientRetryDelaySec = 3,
            maxSameRetries = 1,
            sleeper = { waits.add(it) },
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Success)
        assertEquals(3000L, waits.sum())
        assertTrue(waits.all { it in 1..3000L })
    }

    @Test
    fun testPatientSleep_StopsEarly() = kotlinx.coroutines.runBlocking {
        // 合計秒数は変えず、停止時は残りを捨てること
        val waits = mutableListOf<Long>()
        patientSleep(2500L, stopped = { false }, sleeper = { waits.add(it) })
        assertEquals(2500L, waits.sum())
        assertTrue(waits.all { it in 1..1000L })

        val noWaits = mutableListOf<Long>()
        patientSleep(60000L, stopped = { true }, sleeper = { noWaits.add(it) })
        assertTrue(noWaits.isEmpty())

        var first = true
        var stopNow = false
        val partial = mutableListOf<Long>()
        patientSleep(5000L, stopped = { stopNow }, sleeper = {
            partial.add(it)
            if (first) {
                first = false
                stopNow = true
            }
        })
        assertEquals(1000L, partial.sum())
    }

    @Test
    fun testIsDeterministicFailure() {
        // コンテンツブロック・文脈長超過・品質検査不合格のみ確定失敗（.failed対象）
        assertTrue(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.BLOCKED_DETERMINISTIC))
        assertTrue(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.FATAL, "400-context-length"))
        assertTrue(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.FATAL, "verify-rejected: residual hangul"))

        // 通信エラー、サーバー5xx、429、設定不良は外的要因のため .failed を作らない
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.RETRYABLE_AFTER, "io:timeout"))
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.RETRYABLE_AFTER, "503"))
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.QUOTA_MINUTE))
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.QUOTA_DAILY))
        assertFalse(com.example.novelscraper.translation.v2.domain.isDeterministicFailure(FailureKind.CONFIG))
    }

    @Test
    fun testRotation_RejectsUnmanagedProfiles() = kotlinx.coroutines.runBlocking {
        // 管理巡回器に非管理のみを渡す構成ミスは、プール誤用ではなく即時確定する
        val script: Map<String, MutableList<LlmResult>> = mapOf("x/y" to mutableListOf(quotaMinute()))
        val rotation = Rotation(
            workerId = 1,
            profiles = listOf(V2ModelProfile(providerId = "openrouter", model = "x/y")),
            pool = QuotaPool(emptyList()),
            keyIndex = 0,
            key = "",
            descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR, ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR),
            handlerFactory = { _, _ -> scriptedHandler(script) },
            openRouterKey = "or",
            maxSameRetries = 0,
            log = {}
        )
        val result = rotation.execute(listOf("prompt"), "src")
        assertTrue(result is LlmResult.Failure)
        assertFalse(rotation.exhausted)
    }

    @Test
    fun testResolveProfileOptions_Gating() {
        // Gemma系は思考を落とす。未指定時はGemmaの最大値8192がデフォルト適用
        val gemma = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemma-4-31b-it", thinkingLevel = "high"),
            GEMINI_DESCRIPTOR
        )
        assertNull(gemma.thinkingLevel)
        assertNull(gemma.thinkingBudget)
        assertEquals(8192, gemma.maxOutputTokens)

        // 3.8のminimalは非対応のため落とす（モデル既定に委ねる）。未指定時は3.8の最大値64000がデフォルト適用
        val flash38 = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemini-3.8-flash", thinkingLevel = "minimal"),
            GEMINI_DESCRIPTOR
        )
        assertNull(flash38.thinkingLevel)
        assertEquals(64000, flash38.maxOutputTokens)

        // 対応値は透過。未指定時は3.5の最大値65536がデフォルト適用
        val flash35 = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", thinkingLevel = "low", temperature = 0.5),
            GEMINI_DESCRIPTOR
        )
        assertEquals("low", flash35.thinkingLevel)
        assertEquals(65536, flash35.maxOutputTokens)

        // 手動でモデル上限を超える数値を指定した場合はモデルの最大値に安全にクランプ
        val clamped = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemma-4-31b-it", maxOutputTokens = 999999),
            GEMINI_DESCRIPTOR
        )
        assertEquals(8192, clamped.maxOutputTokens)

        // 記述子なしでも温度は通す（未知プロバイダーの寛容）
        val unknown = resolveProfileOptions(
            V2ModelProfile(providerId = "openrouter", model = "x/y", temperature = 0.5),
            null
        )
        assertEquals(0.5, unknown.temperature)
        assertEquals(65536, unknown.maxOutputTokens)
    }

    @Test
    fun testRunEngine_FolderRun() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel")
        for (name in listOf("a.txt", "b.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        }
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                // 実モデル相当：末尾マーカーは exactly 1つにして返す
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertEquals(1, summary.folders)
        assertEquals(2, summary.completedFiles)
        assertFalse(summary.aborted)
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        assertEquals("これはテストの本文です。勇者が旅に出ました。", store.readText(store.findChild(outDir.uri, "a.txt")!!.uri))
        // 再実行は早期スキップ
        val summary2 = engine.run(listOf(folder.uri), settings)
        assertEquals(2, summary2.completedFiles)
    }

    @Test
    fun testRunEngine_CompletionFlow() = kotlinx.coroutines.runBlocking {
        // 完了までの通し検証：小2件（バッチ）＋大1件（チャンク）＋辞書ありで全件完成すること
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-flow")
        val small = "これはテストの本文です。勇者が旅に出ました。"
        for (name in listOf("s1.txt", "s2.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, small)
        }
        val bigBody = "これは大きな物語の本文です。勇者は果てしない旅を続けました。\n".repeat(700)
        val bigDoc = store.createFile(folder.uri, "big.txt", "text/plain")!!
        store.writeText(bigDoc.uri, bigBody)
        val dictJson = """{"style":"カタカナ","characters":{"勇者":"ユウシャ"},"genders":{}}"""
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val sys = request.systemPrompt
                if (sys.contains("Extract person names") ||
                    sys.contains("Merge the dictionary") ||
                    sys.contains("Review the merged")
                ) {
                    return LlmResult.Success(dictJson)
                }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            dict = com.example.novelscraper.translation.v2.settings.V2DictSettings(
                enabled = true,
                providerId = "gemini",
                model = "gemini-3.5-flash"
            )
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(3, summary.totalFiles)
        assertEquals(3, summary.completedFiles)
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        val names = store.children(outDir.uri).map { it.name }
        assertTrue(names.containsAll(listOf("s1.txt", "s2.txt", "big.txt")))
        assertTrue(names.none { it.endsWith(".failed") })
        assertEquals("これはテストの本文です。勇者が旅に出ました。".replace("勇者", "ユウシャ"), store.readText(store.findChild(outDir.uri, "s1.txt")!!.uri))
        val bigOut = store.readText(store.findChild(outDir.uri, "big.txt")!!.uri)!!
        assertEquals(bigBody.lines().count { it.isNotBlank() }, bigOut.lines().count { it.isNotBlank() })
        assertEquals(
            bigBody.lines().first { it.isNotBlank() }.replace("勇者", "ユウシャ"),
            bigOut.lines().first { it.isNotBlank() }
        )
        assertEquals(
            bigBody.lines().last { it.isNotBlank() }.replace("勇者", "ユウシャ"),
            bigOut.lines().last { it.isNotBlank() }
        )
        // 大ファイル作業所は掃除され、辞書は公開される
        assertTrue(store.children(outDir.uri).none { it.isDirectory })
        assertNotNull(store.findChild(folder.uri, "dictionary.json"))
        // 再実行は早期スキップ
        val summary2 = engine.run(listOf(folder.uri), settings)
        assertEquals(3, summary2.completedFiles)
    }

    @Test
    fun testRunEngine_DictPublishedAndReused() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel")
        for (name in listOf("a.txt", "b.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "勇者タロウは旅に出ました。仲間と共に魔王を倒す決意をしたのです。")
        }
        var dictCalls = 0
        val dictJson = """{"style":"カタカナ","characters":{"勇者タロウ":"タロウ"},"genders":{}}"""
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val sys = request.systemPrompt
                if (sys.contains("Extract person names") ||
                    sys.contains("Merge the dictionary") ||
                    sys.contains("Review the merged")
                ) {
                    dictCalls++
                    return LlmResult.Success(dictJson)
                }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            dict = com.example.novelscraper.translation.v2.settings.V2DictSettings(
                enabled = true,
                providerId = "gemini",
                model = "gemini-3.5-flash"
            )
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(2, summary.completedFiles)
        // 単一バッチのため抽出1＋レビュー1（マージ呼び出しなし）
        assertEquals(2, dictCalls)
        // 確定物はフォルダ直下に公開され、作業所は掃除される（凍結仕様§8・外部フォルダ構成）
        val published = store.findChild(folder.uri, "dictionary.json")
        assertNotNull(published)
        assertTrue((store.readText(published!!.uri) ?: "").contains("タロウ"))
        assertNull(store.findChild(folder.uri, ".dict_building"))
        // 出力だけ消して再実行→辞書は再利用され、再生成されない
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        for (child in store.children(outDir.uri)) {
            store.deleteFile(child.uri)
        }
        val summary2 = engine.run(listOf(folder.uri), settings)
        assertFalse(summary2.aborted)
        assertEquals(2, summary2.completedFiles)
        assertEquals(2, dictCalls)
    }

    @Test
    fun testRunEngine_OpenRouterDictWithoutGeminiKeys() = kotlinx.coroutines.runBlocking {
        // OpenRouter辞書はGeminiプールを使わない（空プールでも誤枯渇しない）
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-or-dict")
        for (name in listOf("a.txt", "b.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "勇者タロウは旅に出ました。仲間と共に魔王を倒す決意をしたのです。")
        }
        val dictJson = """{"style":"カタカナ","characters":{"勇者タロウ":"タロウ"},"genders":{}}"""
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val sys = request.systemPrompt
                if (sys.contains("Extract person names") ||
                    sys.contains("Merge the dictionary") ||
                    sys.contains("Review the merged")
                ) {
                    return LlmResult.Success(dictJson)
                }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = emptyList(),
            openRouterKey = "or-key",
            profiles = listOf(V2ModelProfile(providerId = "openrouter", model = "x/y")),
            limits = V2Limits(requestDelaySec = 0),
            dict = com.example.novelscraper.translation.v2.settings.V2DictSettings(
                enabled = true,
                providerId = "openrouter",
                model = "x/y"
            )
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(2, summary.completedFiles)
        val published = store.findChild(folder.uri, "dictionary.json")
        assertNotNull(published)
        assertTrue((store.readText(published!!.uri) ?: "").contains("タロウ"))
    }

    @Test
    fun testRunEngine_OpenRouterOnlyQuotaRecovers() = kotlinx.coroutines.runBlocking {
        // 経路選択の回帰：OR単独はUnmanagedRotationに乗り、一時429から復帰する（プール誤用なし）
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-or-quota")
        val doc = store.createFile(folder.uri, "a.txt", "text/plain")!!
        store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        val firstCall = java.util.concurrent.atomic.AtomicBoolean(true)
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                if (firstCall.getAndSet(false)) {
                    return LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_MINUTE))
                }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(
                workerStaggerSec = 0,
                minSendIntervalMs = 0L,
                maxSameRetries = 0
            ),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = emptyList(),
            openRouterKey = "or-key",
            profiles = listOf(V2ModelProfile(providerId = "openrouter", model = "x/y")),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0),
            geminiCooldownSec = 0
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(1, summary.completedFiles)
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        assertEquals(
            "これはテストの本文です。勇者が旅に出ました。",
            store.readText(store.findChild(outDir.uri, "a.txt")!!.uri)
        )
    }

    @Test
    fun testRunEngine_ConfigOnlyStopsWorker() = kotlinx.coroutines.runBlocking {
        // 設定不良は全ファイル共通のため、残件を無駄打ちせずワーカー終了する（.failedも作らない）
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-cfg")
        for (name in listOf("a.txt", "b.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        }
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                calls.incrementAndGet()
                return LlmResult.Failure(ClassifiedFailure(FailureKind.CONFIG))
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(
                workerStaggerSec = 0,
                minSendIntervalMs = 0L,
                batchMaxFiles = 1,
                maxSameRetries = 0
            ),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(
                V2ModelProfile(
                    providerId = "gemini",
                    model = "gemini-3.5-flash",
                    promptOrder = listOf(1),
                    useCustomPromptOrder = true
                )
            ),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertEquals(1, calls.get())
        assertEquals(0, summary.completedFiles)
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        assertNull(store.findChild(outDir.uri, "a.txt"))
        assertNull(store.findChild(outDir.uri, "a.txt.failed"))
    }

    @Test
    fun testRunEngine_EmptyDictCachedAndReused() = kotlinx.coroutines.runBlocking {
        // 人名ゼロは空のまま確定・公開され、次回は再生成されない
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-empty-dict")
        for (name in listOf("a.txt", "b.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "風の音だけが聞こえる丘の昼下がりでした。雨上がりの空に雲が流れました。")
        }
        var dictCalls = 0
        val emptyJson = """{"style":"カタカナ","characters":{},"genders":{}}"""
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val sys = request.systemPrompt
                if (sys.contains("Extract person names") ||
                    sys.contains("Merge the dictionary") ||
                    sys.contains("Review the merged")
                ) {
                    dictCalls++
                    return LlmResult.Success(emptyJson)
                }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            dict = com.example.novelscraper.translation.v2.settings.V2DictSettings(
                enabled = true,
                providerId = "gemini",
                model = "gemini-3.5-flash"
            )
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(2, summary.completedFiles)
        assertEquals(1, dictCalls)
        val published = store.findChild(folder.uri, "dictionary.json")
        assertNotNull(published)
        // 空辞書として読込可能（再利用されることが要点。既定値のみのJSON化を許容する）
        val loaded = DictionaryBuilder.parseDictJson(store.readText(published!!.uri) ?: "")
        assertNotNull(loaded)
        assertTrue(loaded!!.characters.isEmpty())
        // 出力だけ消して再実行→空辞書は再利用され、再生成されない
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        for (child in store.children(outDir.uri)) {
            store.deleteFile(child.uri)
        }
        val summary2 = engine.run(listOf(folder.uri), settings)
        assertFalse(summary2.aborted)
        assertEquals(2, summary2.completedFiles)
        assertEquals(1, dictCalls)
    }

    @Test
    fun testRunEngine_DictModelBlankAbortsWithoutFallback() = kotlinx.coroutines.runBlocking {
        // 辞書モデル空欄時はプロファイルや既定モデルへフォールバックせず中断する（利用者指定モデルのみ使用）
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-dict-fallback")
        for (name in listOf("ch1.txt", "ch2.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "主人公の李雲は剣を抜いた。ヒロインの雨花が微笑む。")
        }
        val receivedModels = mutableListOf<String>()
        val dictJson = """{"style":"カタカナ","characters":{"李雲":"リウン","雨花":"ウカ"},"genders":{}}"""
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                receivedModels.add(request.model)
                val sys = request.systemPrompt
                if (sys.contains("Extract person names") ||
                    sys.contains("Merge the dictionary") ||
                    sys.contains("Review the merged")
                ) {
                    return LlmResult.Success(dictJson)
                }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        // 辞書モデルを空文字にする（設定未入力状態を模倣）
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            split = V2SplitSettings(enabled = false), // 物理分割OFF
            dict = com.example.novelscraper.translation.v2.settings.V2DictSettings(
                enabled = true,
                providerId = "gemini",
                model = "", // 空文字
                mergeModel = ""
            )
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertEquals(0, summary.completedFiles)
        assertTrue("空文字モデルへのリクエストが一切ないこと", receivedModels.none { it.isBlank() })
        assertTrue("フォールバック送信がないこと", receivedModels.isEmpty())
        val published = store.findChild(folder.uri, "dictionary.json")
        assertNull("辞書モデル未設定時は辞書ファイルが生成されないこと", published)
        assertNull("作業所を作らず中断すること", store.findChild(folder.uri, ".dict_building"))
    }

    @Test
    fun testDictionaryBuilder_MergeScopeQuotaReported() = kotlinx.coroutines.runBlocking {
        // 抽出モデル≠マージ送信用モデル時、日次枯渇は実際に送ったモデル側のスコープに報告される
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-dict-merge-scope")
        for ((name, body) in listOf("a.txt" to "山田の物語です。", "b.txt" to "鈴木の物語です。")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, body)
        }
        val goodJson = """{"names":["山田"]}"""
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val sys = request.systemPrompt
                return if (sys.contains("Merge the dictionary") || sys.contains("Review the merged")) {
                    LlmResult.Failure(ClassifiedFailure(FailureKind.QUOTA_DAILY))
                } else {
                    LlmResult.Success(goodJson)
                }
            }
        }
        val pool = QuotaPool(listOf("k1"))
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "m-extract")),
            limits = V2Limits(requestDelaySec = 0),
            dict = V2DictSettings(
                enabled = true,
                providerId = "gemini",
                model = "m-extract",
                mergeModel = "m-merge",
                batchMaxBytes = 25,
                requestDelaySec = 0,
                cooldown429Sec = 0
            )
        )
        val files = store.children(folder.uri).filter { !it.isDirectory }.sortedBy { it.name }
        val result = DictionaryBuilder(
            store = store,
            buildHandler = { _, _, _ -> handler },
            stopped = { false },
            log = {}
        ).build(folder.uri, files, settings, pool)
        assertNull("マージ枯渇時は保留（null）", result)
        assertFalse("抽出スコープは枯渇扱いしないこと", pool.isExhausted(listOf("m-extract")))
        assertTrue("実際に送ったマージスコープを枯渇扱いすること", pool.isExhausted(listOf("m-merge")))
    }

    @Test
    fun testRunEngine_FailedFilesNotReprocessed() = kotlinx.coroutines.runBlocking {
        // 単体失敗は既存集合に登録され、後続ワーカーが拾い直さない（低速ワーカーの追いつき対策）
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-nodup")
        for (name in listOf("a.txt", "b.txt", "c.txt")) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        }
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                calls.incrementAndGet()
                return LlmResult.Failure(ClassifiedFailure(FailureKind.BLOCKED_DETERMINISTIC, note = "SAFETY"))
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(
                workerStaggerSec = 1,
                minSendIntervalMs = 0L,
                batchMaxFiles = 1,
                maxSameRetries = 0
            ),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(
                V2ModelProfile(
                    providerId = "gemini",
                    model = "gemini-3.5-flash",
                    promptOrder = listOf(1),
                    useCustomPromptOrder = true
                )
            ),
            limits = V2Limits(parallelWorkers = 2, requestDelaySec = 0)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        // 失敗分も settled 件数に入る。全3件が確定していること
        assertEquals(3, summary.completedFiles)
        // 3件×各1回のみ。修正前は追いつきで6回になっていた
        assertEquals(3, calls.get())
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        assertEquals(
            listOf("a.txt.failed", "b.txt.failed", "c.txt.failed"),
            store.children(outDir.uri).map { it.name }.filter { it != ".lang_cache" }.sorted()
        )
    }

    @Test
    fun testRunEngine_WorkerSurvivesLargeTranslationException() = kotlinx.coroutines.runBlocking {
        // 大ファイル分割翻訳中に作業フォルダ作成や分割処理で予期せぬ例外が発生しても、
        // ワーカーは死なずに.failedを作成し後続ファイルを完走する
        val mem = InMemoryFileStore()
        val store = object : FileStore by mem {
            override suspend fun createDir(parentUri: String, name: String): VDoc? {
                if (name.startsWith(".parts_001_large")) {
                    throw RuntimeException("Simulated unexpected disk/SAF crash during parts creation")
                }
                return mem.createDir(parentUri, name)
            }
        }
        val folder = mem.createRoot("novel-large-resilience")
        val doc1 = mem.createFile(folder.uri, "001_large.txt", "text/plain")!!
        // 60,000バイトの大きなファイル（JAのsplitThresholdBytes 45,000Bを確実に超える）
        store.writeText(doc1.uri, "これは大きなファイルの本文です。\n\n".repeat(1200))
        val doc2 = store.createFile(folder.uri, "002_normal.txt", "text/plain")!!
        store.writeText(doc2.uri, "正常に処理されるべき後続の本文です。")

        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body の日本語訳\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(
                workerStaggerSec = 0,
                minSendIntervalMs = 0L,
                batchMaxFiles = 1
            ),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(
                V2ModelProfile(
                    providerId = "gemini",
                    model = "gemini-3.5-flash",
                    promptOrder = listOf(1),
                    useCustomPromptOrder = true
                )
            ),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse("エンジンが中断終了していないこと", summary.aborted)
        assertEquals(2, summary.completedFiles)

        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        val children = store.children(outDir.uri).map { it.name }.filter { it != ".lang_cache" }.sorted()
        assertTrue("001_large.txt.failed が生成され他ワーカーの連鎖死が防がれること", children.contains("001_large.txt.failed"))
        assertTrue("002_normal.txt がワーカー生存により正常に翻訳保存されること", children.contains("002_normal.txt"))
    }

    @Test
    fun testRunEngine_TransientFailureDoesNotCreateFailedFile() = kotlinx.coroutines.runBlocking {
        // 通信瞬断・5xx・レート制限などの外的要因は.failedを作成せず保留する
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-transient")
        val doc = store.createFile(folder.uri, "test.txt", "text/plain")!!
        store.writeText(doc.uri, "テスト本文")

        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                return LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "503 Service Unavailable"))
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(
                workerStaggerSec = 0,
                minSendIntervalMs = 0L,
                batchMaxFiles = 1,
                maxSameRetries = 0
            ),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(
                V2ModelProfile(
                    providerId = "gemini",
                    model = "gemini-3.5-flash",
                    promptOrder = listOf(1),
                    useCustomPromptOrder = true
                )
            ),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0)
        )
        engine.run(listOf(folder.uri), settings)
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")
        // .failed は作成されない
        val failedFiles = if (outDir != null) {
            store.children(outDir.uri).map { it.name }.filter { it.endsWith(".failed") }
        } else {
            emptyList()
        }
        assertTrue(failedFiles.isEmpty())
    }

    @Test
    fun testRunEngine_LangCacheNoDuplicate() = kotlinx.coroutines.runBlocking {
        // 古い一覧＋自動リネーム環境でも ".lang_cache (1)" を作らない
        // lang_cache は翻訳完了_LLM 内にのみ生成される
        val fake = StaleRenameStore()
        val folder = fake.inner.createRoot("novel-lang")
        val doc = fake.inner.createFile(folder.uri, "a.txt", "text/plain")!!
        fake.inner.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = fake,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0)
        )
        val first = engine.run(listOf(folder.uri), settings)
        assertEquals(1, first.completedFiles)
        val outDir = fake.inner.findChild(folder.uri, "翻訳完了_LLM")!!
        // lang_cache は outputDir 内にのみ存在すること
        assertNotNull(fake.inner.findChild(outDir.uri, ".lang_cache"))
        assertEquals("JA", fake.inner.readText(fake.inner.findChild(outDir.uri, ".lang_cache")!!.uri))
        // 入力フォルダには lang_cache が作られていないこと
        assertNull(fake.inner.findChild(folder.uri, ".lang_cache"))
        // 訳文を消して再作業させる（古い一覧で既存キャッシュを見落とす状況）
        fake.inner.deleteFile(fake.inner.findChild(outDir.uri, "a.txt")!!.uri)
        fake.staleMatcher = { it == ".lang_cache" }
        fake.staleFails = 2
        val second = engine.run(listOf(folder.uri), settings)
        assertFalse(second.aborted)
        assertEquals(1, second.completedFiles)
        val outNames = fake.childNames(outDir.uri)
        assertTrue(outNames.none { it.contains("(1)") })
        assertEquals("JA", fake.inner.readText(fake.inner.findChild(outDir.uri, ".lang_cache")!!.uri))
        assertNotNull(fake.inner.findChild(outDir.uri, "a.txt"))
    }

    @Test
    fun testRunEngine_PreSplit() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel")
        val raw = store.createFile(folder.uri, "long.txt", "text/plain")!!
        store.writeText(raw.uri, "昔々あるところに勇者がいました。\n".repeat(120))
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            split = V2SplitSettings(enabled = true, splitSizeChars = 1000)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertTrue(summary.completedFiles > 1)
        // Parts translate inside the split subfolder; the parent is not translated directly.
        assertNull(store.findChild(folder.uri, settings.limits.outputSubDir))
        // 入力フォルダには lang_cache が作られないこと
        assertNull(store.findChild(folder.uri, ".lang_cache"))
        val splitDir = store.children(folder.uri).firstOrNull { it.isDirectory }
        assertNotNull(splitDir)
        val novelDir = store.children(splitDir!!.uri).firstOrNull { it.isDirectory }
        assertNotNull(novelDir)
        val outDir = store.findChild(novelDir!!.uri, settings.limits.outputSubDir)
        assertNotNull(outDir)
        // lang_cache は各サブフォルダの outputDir 内にのみ存在すること
        assertNotNull(store.findChild(outDir!!.uri, ".lang_cache"))
        assertEquals("JA", store.readText(store.findChild(outDir.uri, ".lang_cache")!!.uri))
        val outputs = store.children(outDir.uri).filter { it.name.endsWith(".txt") }
        assertTrue(outputs.isNotEmpty())
        assertTrue(outputs.all { it.name.startsWith("part_") })
        assertEquals(summary.completedFiles, outputs.size)
    }

    @Test
    fun testRunEngine_PreSplitSkipsMojibake() = kotlinx.coroutines.runBlocking {
        // 文字化け確定ファイルは翻訳しない（通常経路へのすり抜けなし）。正常ファイルは訳す。
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-moji")
        val good = store.createFile(folder.uri, "good.txt", "text/plain")!!
        store.writeText(good.uri, "昔々あるところに勇者がいました。\n".repeat(120))
        val bad = store.createFile(folder.uri, "bad.txt", "text/plain")!!
        val pattern = byteArrayOf(
            0x00.toByte(), 0x98.toByte(), 0x81.toByte(),
            0x8D.toByte(), 0xFF.toByte(), 0x80.toByte()
        )
        store.writeBytes(bad.uri, ByteArray(2000) { i -> pattern[i % pattern.size] })
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            split = V2SplitSettings(enabled = true, splitSizeChars = 1000)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertTrue(summary.completedFiles > 0)
        assertEquals(summary.totalFiles, summary.completedFiles)
        // bad に subfolder・出力・.failed のいずれも作られない
        val splitRoot = store.findChild(folder.uri, "分割済み")!!
        assertNotNull(store.findChild(splitRoot.uri, "good"))
        assertNull(store.findChild(splitRoot.uri, "bad"))
        assertNull(store.findChild(folder.uri, settings.limits.outputSubDir))
        val goodOut = store.findChild(store.findChild(splitRoot.uri, "good")!!.uri, settings.limits.outputSubDir)!!
        val names = store.children(goodOut.uri).map { it.name }
        assertTrue(names.none { it.contains("bad") || it.endsWith(".failed") })
    }

    @Test
    fun testRunEngine_AutoPromptOrder() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel")
        val doc = store.createFile(folder.uri, "a.txt", "text/plain")!!
        store.writeText(doc.uri, "주인공은 평범한 소년이었다。\n어느 날 신비한 힘을 각성했다。\n")
        val seen = mutableListOf<String>()
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                synchronized(seen) { seen.add(request.systemPrompt) }
                return LlmResult.Success("主人公は平凡な少年だった。\nある日不思議な力に覚醒した。\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0),
            promptSelection = com.example.novelscraper.translation.v2.settings.V2PromptSelection(autoEnabled = true)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(1, summary.completedFiles)
        // Korean source + auto => prompt 3 (Hanja name rules), not prompt 1.
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.any { it.contains("Hanja") })
    }

    @Test
    fun testRunEngine_PrevTailInjected() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel")
        val texts = listOf(
            "a.txt" to "勇者タロウは朝早く起きました。\n装備を整えて城を出ました。\n",
            "b.txt" to "仲間ジロウは川で魚を釣りました。\n昼には宿に戻りました。\n",
            "c.txt" to "魔王サブロウは城で笑いました。\n部下に命令を出しました。\n",
            "d.txt" to "決戦の日が来ました。\n皆で力を合わせました。\n"
        )
        for ((name, text) in texts) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, text)
        }
        val seen = mutableListOf<Pair<String, String>>()
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                synchronized(seen) { seen.add(request.userText to request.systemPrompt) }
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0),
            prevContext = V2PrevContext(enabled = true, lines = 20)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(4, summary.completedFiles)
        // First batch (a+b+c) carries no previous-story context.
        val batchCalls = seen.filter { it.first.contains("仲間ジロウ") && it.first.contains("勇者タロウ") }
        assertTrue(batchCalls.isNotEmpty())
        assertTrue(batchCalls.none { it.second.contains("PREVIOUS TEXT") })
        // File d follows c: its prompt carries c's raw tail.
        val dCalls = seen.filter { it.first.contains("決戦の日が来ました") }
        assertTrue(dCalls.isNotEmpty())
        assertTrue(dCalls.all { it.second.contains("PREVIOUS TEXT") })
        assertTrue(dCalls.all { it.second.contains("魔王サブロウ") })
    }

    @Test
    fun testRunEngine_ParallelWorkers_PrevTailInjected() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel_multi")
        val texts = listOf(
            "ch01.txt" to "第1話の文章です。\n勇者は旅立ちました。\n",
            "ch02.txt" to "第2話の文章です。\n森を抜けました。\n",
            "ch03.txt" to "第3話の文章です。\n洞窟に入りました。\n",
            "ch04.txt" to "第4話の文章です。\n宝箱を見つけました。\n"
        )
        for ((name, text) in texts) {
            val doc = store.createFile(folder.uri, name, "text/plain")!!
            store.writeText(doc.uri, text)
        }
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                seen.add(request.userText to request.systemPrompt)
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.IO),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L, batchMaxFiles = 1),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1", "k2"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(parallelWorkers = 2, requestDelaySec = 0),
            prevContext = V2PrevContext(enabled = true, lines = 20)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(4, summary.completedFiles)

        val ch2 = seen.firstOrNull { it.first.contains("第2話の文章") }
        assertNotNull(ch2)
        assertTrue(ch2!!.second.contains("勇者は旅立ちました"))

        val ch3 = seen.firstOrNull { it.first.contains("第3話の文章") }
        assertNotNull(ch3)
        assertTrue(ch3!!.second.contains("森を抜けました"))
    }

    @Test
    fun testRunEngine_PreSplit_DictFailed_DoesNotTranslateRawParent() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val raw = store.createFile(root.uri, "large_story.txt", "text/plain")!!
        store.writeText(raw.uri, "主人公キム・ミンジュンが旅に出た。\n".repeat(200))

        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.IO),
            handlerFactory = { _, profile, _ ->
                object : ProviderHandler {
                    override suspend fun call(request: LlmRequest): LlmResult {
                        if (request.model == "dict-model") {
                            // 辞書生成APIが失敗した場合
                            return LlmResult.Failure(ClassifiedFailure(FailureKind.CONFIG))
                        }
                        return LlmResult.Success("訳文\n[SRC_END]")
                    }
                }
            }
        )
        val settings = V2Settings(
            geminiKeys = listOf("key1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            dict = V2DictSettings(
                enabled = true,
                model = "dict-model"
            ),
            split = V2SplitSettings(
                enabled = true,
                splitSizeChars = 500
            )
        )
        val summary = engine.run(listOf(root.uri), settings)
        // 辞書未完成のため翻訳は中断・スキップされ、親ファイルの生テキストが直接翻訳される事故（completedFiles > 0）がないこと
        assertEquals(0, summary.completedFiles)
    }

    @Test
    fun testRunEngine_LargeDeterministicStallBecomesFailed() = kotlinx.coroutines.runBlocking {
        // 同一記録での未解決停止が続くと親失敗記録に終端すること（永遠の保持にしない）
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-large-dead")
        val doc = store.createFile(folder.uri, "001_large.txt", "text/plain")!!
        store.writeText(doc.uri, "これは大きなファイルの本文です。\n\n".repeat(1200))
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                return LlmResult.Failure(ClassifiedFailure(FailureKind.BLOCKED_DETERMINISTIC, note = "blocked"))
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L, batchMaxFiles = 1),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0)
        )
        engine.run(listOf(folder.uri), settings)
        val outDir = store.findChild(folder.uri, "翻訳完了_LLM")!!
        // 1回目では親失敗記録を作らない（保持のみ）
        assertNull(store.findChild(outDir.uri, "001_large.txt.failed"))
        engine.run(listOf(folder.uri), settings)
        assertNull(store.findChild(outDir.uri, "001_large.txt.failed"))
        // 3回目の連続停止で終端し、親失敗記録ができる
        engine.run(listOf(folder.uri), settings)
        assertNotNull(store.findChild(outDir.uri, "001_large.txt.failed"))
    }

    @Test
    fun testLangCache_LegacyTripleMigratesToSingle() = kotlinx.coroutines.runBlocking {
        // 旧三重配置（入力の正規名＋互換名＋衝突変種）が単一正本に収束すること
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-tri")
        val doc = store.createFile(folder.uri, "a.txt", "text/plain")!!
        store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        store.writeText(store.createFile(folder.uri, LangCacheStore.FILE_NAME, "application/octet-stream")!!.uri, "JA")
        store.writeText(store.createFile(folder.uri, ".lang_cache.txt", "application/octet-stream")!!.uri, "JA")
        store.createFile(folder.uri, ".lang_cache (1)", "application/octet-stream")
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertEquals(1, summary.completedFiles)
        val outDir = store.findChild(folder.uri, settings.limits.outputSubDir)!!
        // 出力には正本が1つだけ
        assertEquals("JA", store.readText(store.findChild(outDir.uri, LangCacheStore.FILE_NAME)!!.uri))
        assertNull(store.findChild(outDir.uri, ".lang_cache.txt"))
        assertTrue(store.children(outDir.uri).none { it.name.contains("(1)") })
        // 入力の旧配置は全削除
        assertNull(store.findChild(folder.uri, LangCacheStore.FILE_NAME))
        assertNull(store.findChild(folder.uri, ".lang_cache.txt"))
        assertTrue(store.children(folder.uri).none { it.name.startsWith(".lang_cache") })
    }

    @Test
    fun testLangCache_OutputPinWinsOverRedetect() = kotlinx.coroutines.runBlocking {
        // 出力側の固定値（ZH）は本文がJAでも上書きされず、二重化もしないこと
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-pin")
        val doc = store.createFile(folder.uri, "a.txt", "text/plain")!!
        store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        val outDir = store.createDir(folder.uri, "翻訳完了_LLM")!!
        store.writeText(store.createFile(outDir.uri, LangCacheStore.FILE_NAME, "application/octet-stream")!!.uri, "ZH")
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0)
        )
        engine.run(listOf(folder.uri), settings)
        assertEquals("ZH", store.readText(store.findChild(outDir.uri, LangCacheStore.FILE_NAME)!!.uri))
        assertNull(store.findChild(folder.uri, LangCacheStore.FILE_NAME))
        assertTrue(store.children(outDir.uri).none { it.name.contains("(1)") })
    }

    @Test
    fun testLangCache_OutputDedupeKeepsCanonical() = kotlinx.coroutines.runBlocking {
        // 同一出力内の正規＋互換の二重は正規に一本化されること
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-dedupe")
        val doc = store.createFile(folder.uri, "a.txt", "text/plain")!!
        store.writeText(doc.uri, "これはテストの本文です。勇者が旅に出ました。")
        val outDir = store.createDir(folder.uri, "翻訳完了_LLM")!!
        store.writeText(store.createFile(outDir.uri, LangCacheStore.FILE_NAME, "application/octet-stream")!!.uri, "JA")
        store.writeText(store.createFile(outDir.uri, ".lang_cache.txt", "application/octet-stream")!!.uri, "EN")
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0)
        )
        engine.run(listOf(folder.uri), settings)
        assertEquals("JA", store.readText(store.findChild(outDir.uri, LangCacheStore.FILE_NAME)!!.uri))
        assertNull(store.findChild(outDir.uri, ".lang_cache.txt"))
    }

    @Test
    fun testRunEngine_OnDemand_SplitTranslateInterleaved() = kotlinx.coroutines.runBlocking {
        // オンデマンド回帰: aの翻訳開始がbの物理分割より先であること（全件先行分割の復活を検出）。
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-ondemand")
        val bodyA = "昔々あるところに勇者あいました。\n".repeat(120)
        val bodyB = "遠い昔に魔法使いびいました。\n".repeat(120)
        store.writeText(store.createFile(folder.uri, "a.txt", "text/plain")!!.uri, bodyA)
        store.writeText(store.createFile(folder.uri, "b.txt", "text/plain")!!.uri, bodyB)
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            split = V2SplitSettings(enabled = true, splitSizeChars = 1000)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertTrue(summary.completedFiles > 2)
        val logs = engine.state.value.logs
        val splitA = logs.indexOfFirst { it.contains("[物理分割開始]") && it.contains("a.txt") }
        val translateA = logs.indexOfFirst { it.contains("[翻訳開始]") && it.contains("a") }
        val splitB = logs.indexOfFirst { it.contains("[物理分割開始]") && it.contains("b.txt") }
        assertTrue("aの分割ログがあること", splitA >= 0)
        assertTrue("aの翻訳開始ログがあること", translateA >= 0)
        assertTrue("bの分割ログがあること", splitB >= 0)
        assertTrue("aの翻訳開始がbの分割より先であること（オンデマンド）", translateA < splitB)
    }

    @Test
    fun testRunEngine_OnDemand_CompletedSplitsKept() = kotlinx.coroutines.runBlocking {
        // 完了分の分割済みは残り、再開時に再利用されること。
        val store = InMemoryFileStore()
        val folder = store.createRoot("novel-keep")
        store.writeText(
            store.createFile(folder.uri, "a.txt", "text/plain")!!.uri,
            "昔々あるところに勇者あいました。\n".repeat(120)
        )
        store.writeText(
            store.createFile(folder.uri, "b.txt", "text/plain")!!.uri,
            "遠い昔に魔法使いびいました。\n".repeat(120)
        )
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        val engine = RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(requestDelaySec = 0),
            split = V2SplitSettings(enabled = true, splitSizeChars = 1000)
        )
        val first = engine.run(listOf(folder.uri), settings)
        assertFalse(first.aborted)
        val splitRoot = store.findChild(folder.uri, "分割済み")
        assertNotNull(splitRoot)
        assertNotNull(store.findChild(splitRoot!!.uri, "a"))
        assertNotNull(store.findChild(splitRoot.uri, "b"))
        val second = engine.run(listOf(folder.uri), settings)
        assertFalse(second.aborted)
        assertNotNull(store.findChild(splitRoot.uri, "a"))
        assertNotNull(store.findChild(splitRoot.uri, "b"))
    }
}
