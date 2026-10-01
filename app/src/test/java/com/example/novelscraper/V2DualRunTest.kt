package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.engine.EngineOptions
import com.example.novelscraper.translation.v2.engine.FolderTarget
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.settings.V2Limits
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test

/**
 * 2エンジン同時実行の検証（共有送信ゲート・独立状態）。
 * 技術的根拠1行：並列化の分離単位はエンジン実体であり、ゲート共有と状態独立をここで縛る。
 */
class V2DualRunTest {

    private fun echoEngine(store: InMemoryFileStore, gate: V2SendGate): RunEngine {
        val handler = object : ProviderHandler {
            override suspend fun call(request: LlmRequest): LlmResult {
                val body = request.userText.trimEnd().removeSuffix("[SRC_END]").trimEnd()
                return LlmResult.Success("$body\n[SRC_END]")
            }
        }
        return RunEngine(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            options = EngineOptions(workerStaggerSec = 0, minSendIntervalMs = 0L),
            handlerFactory = { _, _, _ -> handler },
            sharedSendGate = gate
        )
    }

    private fun settings(model: String) = V2Settings(
        geminiKeys = listOf("k1"),
        profiles = listOf(V2ModelProfile(providerId = "gemini", model = model)),
        limits = V2Limits(parallelWorkers = 1, requestDelaySec = 0)
    )

    @Test
    fun testDualEngines_RunConcurrently() = kotlinx.coroutines.runBlocking {
        val gate = V2SendGate()
        val storeZh = InMemoryFileStore()
        val folderZh = storeZh.createRoot("novel-zh")
        val docZh = storeZh.createFile(folderZh.uri, "a.txt", "text/plain")!!
        storeZh.writeText(docZh.uri, "昔々あるところに勇者がいました。\n".repeat(20))
        val storeKo = InMemoryFileStore()
        val folderKo = storeKo.createRoot("novel-ko")
        val docKo = storeKo.createFile(folderKo.uri, "b.txt", "text/plain")!!
        storeKo.writeText(docKo.uri, "昔々あるところに勇者がいました。\n".repeat(20))

        val engineZh = echoEngine(storeZh, gate)
        val engineKo = echoEngine(storeKo, gate)
        val zh = async {
            engineZh.runWithTargets(listOf(FolderTarget(folderZh.uri, "novel-zh")), settings("gemini-flash"))
        }
        val ko = async {
            engineKo.runWithTargets(listOf(FolderTarget(folderKo.uri, "novel-ko")), settings("gemini-lite"))
        }
        val (summaryZh, summaryKo) = awaitAll(zh, ko)
        assertFalse(summaryZh.aborted)
        assertFalse(summaryKo.aborted)
        assertEquals(1, summaryZh.completedFiles)
        assertEquals(1, summaryKo.completedFiles)
        // 状態は独立（ログが混ざらない）
        assertTrue(engineZh.state.value.logs.any { it.contains("novel-zh") })
        assertTrue(engineKo.state.value.logs.any { it.contains("novel-ko") })
        assertTrue(engineZh.state.value.logs.none { it.contains("novel-ko") })
        assertTrue(engineKo.state.value.logs.none { it.contains("novel-zh") })
    }
}
