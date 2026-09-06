package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.engine.EngineOptions
import com.example.novelscraper.translation.v2.engine.Rotation
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.engine.resolveProfileOptions
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
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
            descriptors = mapOf("gemini" to GEMINI_DESCRIPTOR, "openrouter" to OPENROUTER_DESCRIPTOR),
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
            descriptors = mapOf("gemini" to GEMINI_DESCRIPTOR),
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
            descriptors = mapOf("gemini" to GEMINI_DESCRIPTOR),
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
            descriptors = mapOf("gemini" to GEMINI_DESCRIPTOR),
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
    fun testResolveProfileOptions_Gating() {
        // Gemma系は思考を落とす
        val gemma = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemma-4-31b-it", thinkingLevel = "high"),
            GEMINI_DESCRIPTOR
        )
        assertNull(gemma.thinkingLevel)
        assertNull(gemma.thinkingBudget)
        // 3.8のminimalは非対応のため落とす（モデル既定に委ねる）
        val flash38 = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemini-3.8-flash", thinkingLevel = "minimal"),
            GEMINI_DESCRIPTOR
        )
        assertNull(flash38.thinkingLevel)
        // 対応値は透過
        val flash35 = resolveProfileOptions(
            V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", thinkingLevel = "low", temperature = 0.5),
            GEMINI_DESCRIPTOR
        )
        assertEquals("low", flash35.thinkingLevel)
        // 記述子なしでも温度は通す（未知プロバイダーの寛容）
        val unknown = resolveProfileOptions(
            V2ModelProfile(providerId = "openrouter", model = "x/y", temperature = 0.5),
            null
        )
        assertEquals(0.5, unknown.temperature)
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
            options = EngineOptions(workerStaggerSec = 0),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash"))
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
            options = EngineOptions(workerStaggerSec = 0),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
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
            options = EngineOptions(workerStaggerSec = 0),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            split = V2SplitSettings(enabled = true, splitSizeChars = 1000)
        )
        val summary = engine.run(listOf(folder.uri), settings)
        assertFalse(summary.aborted)
        assertTrue(summary.completedFiles > 1)
        // Parts translate inside the split subfolder; the parent is not translated directly.
        assertNull(store.findChild(folder.uri, settings.limits.outputSubDir))
        val splitDir = store.children(folder.uri).firstOrNull { it.isDirectory }
        assertNotNull(splitDir)
        val novelDir = store.children(splitDir!!.uri).firstOrNull { it.isDirectory }
        assertNotNull(novelDir)
        val outDir = store.findChild(novelDir!!.uri, settings.limits.outputSubDir)
        assertNotNull(outDir)
        val outputs = store.children(outDir!!.uri).filter { it.name.endsWith(".txt") }
        assertTrue(outputs.isNotEmpty())
        assertTrue(outputs.all { it.name.startsWith("part_") })
        assertEquals(summary.completedFiles, outputs.size)
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
            options = EngineOptions(workerStaggerSec = 0),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(parallelWorkers = 1),
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
            options = EngineOptions(workerStaggerSec = 0),
            handlerFactory = { _, _, _ -> handler }
        )
        val settings = V2Settings(
            geminiKeys = listOf("k1"),
            profiles = listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash")),
            limits = V2Limits(parallelWorkers = 1),
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
}
