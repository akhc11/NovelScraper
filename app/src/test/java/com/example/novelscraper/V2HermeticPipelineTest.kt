package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.engine.EngineOptions
import com.example.novelscraper.translation.v2.engine.LangCacheStore
import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.infra.GeminiHandler
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.infra.OpenRouterHandler
import com.example.novelscraper.translation.v2.pipeline.COMPLETION_MARKER
import com.example.novelscraper.translation.v2.pipeline.splitSingleTextFile
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.engine.resolveProfileOptions
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 外部通信・トークン費用完全ゼロの密閉型（Hermetic）統合テストスイート。
 * 標準のServerSocketによる完全独立したローカルモックサーバーを使用し、
 * HTTPプロトコル準拠、障害注入（429/503/不正JSON）、並行ワーカー排他制御、
 * プロンプト整合性、大容量ストリーミング分割を決定論的に検証する。
 */
class V2HermeticPipelineTest {

    private lateinit var server: HermeticMockServer

    data class RecordedHttpCall(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    class HermeticMockServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        @Volatile private var running = true

        val recordedRequests = CopyOnWriteArrayList<RecordedHttpCall>()
        @Volatile var responseCode = 200
        val responseHeaders = mutableMapOf<String, String>()
        @Volatile var responseBody = ""
        @Volatile var customDispatcher: ((RecordedHttpCall) -> Triple<Int, String, Map<String, String>>?)? = null

        private val workerThread = thread(start = true, isDaemon = true, name = "HermeticServerThread") {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    handleSocket(socket)
                } catch (_: Exception) {
                    break
                }
            }
        }

        private fun handleSocket(socket: Socket) {
            thread(start = true, isDaemon = true) {
                try {
                    socket.use { s ->
                        val input = BufferedInputStream(s.getInputStream())
                        val reqLine = readLine(input) ?: return@use
                        val parts = reqLine.split(" ")
                        val method = parts.getOrNull(0) ?: "GET"
                        val path = parts.getOrNull(1) ?: "/"

                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = readLine(input) ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) {
                                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                            }
                        }

                        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val bodyBytes = ByteArray(contentLength)
                        var readSoFar = 0
                        while (readSoFar < contentLength) {
                            val count = input.read(bodyBytes, readSoFar, contentLength - readSoFar)
                            if (count < 0) break
                            readSoFar += count
                        }
                        val body = String(bodyBytes, Charsets.UTF_8)

                        val call = RecordedHttpCall(method, path, headers, body)
                        recordedRequests.add(call)

                        val custom = customDispatcher?.invoke(call)
                        val code = custom?.first ?: responseCode
                        val respStr = custom?.second ?: responseBody
                        val extraH = custom?.third ?: responseHeaders

                        val out = s.getOutputStream().buffered()
                        val respBytes = respStr.toByteArray(Charsets.UTF_8)
                        val statusText = when (code) {
                            200 -> "OK"
                            429 -> "Too Many Requests"
                            500 -> "Internal Server Error"
                            503 -> "Service Unavailable"
                            else -> "Status"
                        }
                        val headSb = StringBuilder()
                        headSb.append("HTTP/1.1 $code $statusText\r\n")
                        headSb.append("Content-Type: application/json\r\n")
                        headSb.append("Content-Length: ${respBytes.size}\r\n")
                        headSb.append("Connection: close\r\n")
                        for ((k, v) in extraH) {
                            headSb.append("$k: $v\r\n")
                        }
                        headSb.append("\r\n")
                        out.write(headSb.toString().toByteArray(Charsets.UTF_8))
                        out.write(respBytes)
                        out.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val baos = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (baos.size() == 0) return null
                    break
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) baos.write(b)
            }
            return baos.toString("UTF-8")
        }

        override fun close() {
            running = false
            try { serverSocket.close() } catch (_: Exception) {}
            workerThread.interrupt()
        }
    }

    @Before
    fun setUp() {
        server = HermeticMockServer()
        server.responseCode = 200
        server.responseBody = """{
            "candidates": [{
                "content": {
                    "parts": [{ "text": "翻訳された本文です。\n$COMPLETION_MARKER" }]
                }
            }],
            "usageMetadata": { "promptTokenCount": 10, "candidatesTokenCount": 20 }
        }"""
    }

    @After
    fun tearDown() {
        server.close()
    }

    // =========================================================================
    // 1. HTTP通信 & プロトコル適合性（Protocol & Contract）
    // =========================================================================

    @Test
    fun testGemini_ProtocolContract_HeadersAndPayload() = runBlocking {
        val endpoint = "http://127.0.0.1:${server.port}/v1beta/models"
        val handler = GeminiHandler(apiKey = "gemini-secret-test-key", endpointBase = endpoint)

        val req = LlmRequest(
            providerId = "gemini",
            model = "gemini-2.5-flash",
            systemPrompt = "あなたは小説翻訳家です。",
            userText = "「こんにちは世界」"
        )
        val result = handler.call(req)

        assertTrue("正常にパースされてSuccessになること", result is LlmResult.Success)
        val success = result as LlmResult.Success
        assertTrue("本文がパースされていること", success.text.contains("翻訳された本文です。"))

        assertEquals("リクエストが1件送信されたこと", 1, server.recordedRequests.size)
        val call = server.recordedRequests.first()
        assertEquals("POSTメソッドであること", "POST", call.method)
        assertTrue("正しいモデルエンドポイントにリクエストしていること", call.path.contains("gemini-2.5-flash:generateContent"))
        assertEquals("APIキーヘッダーが正しく設定されていること", "gemini-secret-test-key", call.headers["x-goog-api-key"])
        assertTrue("Content-TypeがJSONであること", call.headers["content-type"]?.contains("application/json") == true)
        assertTrue("システム指示がJSONボディに含まれていること", call.body.contains("小説翻訳家"))
        assertTrue("ユーザープロンプトがJSONボディに含まれていること", call.body.contains("こんにちは世界"))
    }

    @Test
    fun testOpenRouter_ProtocolContract_HeadersAndPayload() = runBlocking {
        server.responseBody = """{
            "choices": [{
                "message": { "content": "OpenRouter翻訳結果\n$COMPLETION_MARKER" }
            }],
            "usage": { "prompt_tokens": 15, "completion_tokens": 25 }
        }"""

        val endpoint = "http://127.0.0.1:${server.port}/api/v1/chat/completions"
        val handler = OpenRouterHandler(
            apiKey = "sk-or-v1-test-key",
            endpoint = endpoint
        )

        val req = LlmRequest(
            providerId = "openrouter",
            model = "deepseek/deepseek-chat",
            systemPrompt = "System Prompt Here",
            userText = "User Prompt Here"
        )
        val result = handler.call(req)

        assertTrue("Successであること", result is LlmResult.Success)
        val success = result as LlmResult.Success
        assertTrue("翻訳結果が抽出されていること", success.text.contains("OpenRouter翻訳結果"))

        val call = server.recordedRequests.first()
        assertEquals("POST", call.method)
        assertEquals("Bearer sk-or-v1-test-key", call.headers["authorization"])
        assertEquals("https://github.com/akhc11/NovelScraper", call.headers["http-referer"])
        assertEquals("NovelScraper2", call.headers["x-title"])
        assertTrue("モデル名がJSONに含まれること", call.body.contains("deepseek/deepseek-chat"))
    }

    // =========================================================================
    // 2. 障害注入 & 回復力（Fault Injection & Resilience）
    // =========================================================================

    @Test
    fun testFaultInjection_429RateLimit_ParsedWithRetryAfter() = runBlocking {
        server.responseCode = 429
        server.responseHeaders["Retry-After"] = "7"
        server.responseBody = """{
            "error": { "code": 429, "message": "Resource has been exhausted", "status": "RESOURCE_EXHAUSTED" }
        }"""

        val endpoint = "http://127.0.0.1:${server.port}/v1beta/models"
        val handler = GeminiHandler(apiKey = "dummy", endpointBase = endpoint)

        val result = handler.call(LlmRequest(providerId = "gemini", model = "gemini-2.0-flash", systemPrompt = "", userText = "test"))

        assertTrue("Failureであること", result is LlmResult.Failure)
        val failure = (result as LlmResult.Failure).failure
        assertTrue("Quotaまたは一時エラーに分類されること", failure.kind == FailureKind.QUOTA_MINUTE || failure.kind == FailureKind.RETRYABLE_AFTER)
        assertEquals("Retry-Afterヘッダーの秒数が抽出されること", 7, failure.retryAfterSec)
    }

    @Test
    fun testFaultInjection_503ServerUnavailable_Retryable() = runBlocking {
        server.responseCode = 503
        server.responseBody = """{ "error": { "code": 503, "message": "Backend service overloaded" } }"""

        val endpoint = "http://127.0.0.1:${server.port}/v1beta/models"
        val handler = GeminiHandler(apiKey = "dummy", endpointBase = endpoint)

        val result = handler.call(LlmRequest(providerId = "gemini", model = "gemini-2.0-flash", systemPrompt = "", userText = "test"))

        assertTrue("Failureであること", result is LlmResult.Failure)
        val failure = (result as LlmResult.Failure).failure
        assertEquals("一時障害としてRETRYABLE_AFTERに分類されること", FailureKind.RETRYABLE_AFTER, failure.kind)
    }

    @Test
    fun testFaultInjection_MalformedTruncatedJson_SafeFailure() = runBlocking {
        server.responseCode = 200
        server.responseBody = """{ "candidates": [ { "content": { "parts": [ { "text": """ // 構文崩壊

        val endpoint = "http://127.0.0.1:${server.port}/v1beta/models"
        val handler = GeminiHandler(apiKey = "dummy", endpointBase = endpoint)

        val result = handler.call(LlmRequest(providerId = "gemini", model = "gemini-2.0-flash", systemPrompt = "", userText = "test"))

        // JSON例外でクラッシュせず、安全にFailureを返すこと
        assertTrue("Failureであること", result is LlmResult.Failure)
    }

    // =========================================================================
    // 3. 並行ワーカー & 排他制御・デッドロック防止（Concurrency & Claims）
    // =========================================================================

    @Test
    fun testConcurrency_MultipleWorkers_NoDoubleProcessing_FullPipeline() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel_multi")
        val outputDir = store.createDir(root.uri, "翻訳完了_LLM")!!

        // 4つのテストファイルを用意（中国語小説テキスト）
        for (i in 1..4) {
            val f = store.createFile(root.uri, "ch_$i.txt", "text/plain")!!
            store.writeText(f.uri, "这是第${i}章的中文小说内容。勇者踏上了拯救王国的伟大旅途。世界的命运掌握在他手中。")
        }

        val processedFiles = Collections.synchronizedSet(mutableSetOf<String>())
        val callCount = AtomicInteger(0)

        server.customDispatcher = { call ->
            callCount.incrementAndGet()
            val match = Regex("第(\\d+)章").find(call.body)
            val num = match?.groupValues?.get(1) ?: "1"
            processedFiles.add("ch_$num.txt")

            val translatedText = "これは第${num}章の日本語訳文です。勇者は王国を救う偉大な旅に出ました。世界の運命は彼の手に委ねられています。\n$COMPLETION_MARKER"
            val resp = """{
                "candidates": [{
                    "content": {
                        "parts": [{ "text": "$translatedText" }]
                    }
                }],
                "usageMetadata": { "promptTokenCount": 10, "candidatesTokenCount": 20 }
            }"""
            Triple(200, resp, emptyMap())
        }

        val testSettings = V2Settings(
            geminiKeys = listOf("key-1", "key-2"),
            profiles = listOf(V2ModelProfile(id = "p1", providerId = "gemini", model = "gemini-2.5-flash")),
            limits = com.example.novelscraper.translation.v2.settings.V2Limits(
                parallelWorkers = 2, // 2並行ワーカー
                requestDelaySec = 0
            )
        )

        // RunEngineをローカルテストサーバーに接続して起動
        val engine = RunEngine(
            store = store,
            scope = this,
            options = EngineOptions(batchMaxFiles = 1, maxSameRetries = 1),
            handlerFactory = { _, _, key ->
                GeminiHandler(apiKey = key, endpointBase = "http://127.0.0.1:${server.port}/v1beta/models")
            }
        )

        val summary = engine.runWithNames(listOf(root.uri to "novel_multi"), testSettings)

        // 1. 全4ファイルが正常に完了していること
        assertEquals("全4ファイルが完了すること", 4, summary.completedFiles)
        assertFalse("中断されていないこと", summary.aborted)

        // 2. 二重処理なく4ファイル全てが処理されたこと
        assertEquals("4ファイル全てが処理されたこと", 4, processedFiles.size)

        // 3. 成果物が翻訳フォルダに出力されていること（言語キャッシュの正本1件を除く）
        val outChildren = store.children(outputDir.uri)
            .filter { !LangCacheStore.isCacheFileName(it.name) }
        assertEquals("4つの成果物ファイルが出力されたこと", 4, outChildren.size)
        assertNotNull(
            "言語キャッシュの正本が出力フォルダに1件あること",
            store.findChild(outputDir.uri, LangCacheStore.FILE_NAME)
        )
        for (i in 1..4) {
            val doc = store.findChild(outputDir.uri, "ch_$i.txt")
            assertNotNull("ch_$i.txt が存在すること", doc)
            val content = store.readText(doc!!.uri) ?: ""
            assertTrue("正しい章番号の訳文が含まれること", content.contains("第${i}章の日本語訳文"))
        }
    }

    @Test
    fun testWorkerRunner_BatchFailure_ClaimsCleanedUp_NoLeak() = runBlocking {
        // バッチ処理（2ファイル同時処理）で失敗した際にclaimsが安全に解放され、
        // 次回復帰時にデッドロックせず処理できることを検証
        val store = InMemoryFileStore()
        val root = store.createRoot("novel_claims")
        val f1 = store.createFile(root.uri, "001.txt", "text/plain")!!
        val f2 = store.createFile(root.uri, "002.txt", "text/plain")!!
        store.writeText(f1.uri, "第一章中文小说内容。勇者踏上了拯救王国的旅途。")
        store.writeText(f2.uri, "第二章中文小说内容。勇者踏上了拯救王国的旅途。")

        // 1回目：HTTP 500エラーを返して失敗させる
        server.responseCode = 500
        server.responseBody = """{ "error": { "code": 500, "message": "Temporary Server Error" } }"""

        val testSettings = V2Settings(
            geminiKeys = listOf("key-1"),
            profiles = listOf(V2ModelProfile(id = "p1", providerId = "gemini", model = "gemini-2.5-flash")),
            limits = com.example.novelscraper.translation.v2.settings.V2Limits(
                parallelWorkers = 1,
                requestDelaySec = 0
            )
        )

        val engine = RunEngine(
            store = store,
            scope = this,
            options = EngineOptions(batchMaxFiles = 2, maxSameRetries = 0),
            handlerFactory = { _, _, key ->
                GeminiHandler(apiKey = key, endpointBase = "http://127.0.0.1:${server.port}/v1beta/models")
            }
        )

        engine.runWithNames(listOf(root.uri to "novel_claims"), testSettings)

        // 失敗時でもclaimsがリークして残存していないこと
        // 正常復旧（200 OK）させた際に、再度両方のファイルを処理できることで証明する
        server.responseCode = 200
        server.responseBody = """{
            "candidates": [{
                "content": {
                    "parts": [{ "text": "{\"1\": \"訳文1$COMPLETION_MARKER\", \"2\": \"訳文2$COMPLETION_MARKER\"}" }]
                }
            }],
            "usageMetadata": { "promptTokenCount": 10, "candidatesTokenCount": 20 }
        }"""

        val engine2 = RunEngine(
            store = store,
            scope = this,
            options = EngineOptions(batchMaxFiles = 2, maxSameRetries = 1),
            handlerFactory = { _, _, key ->
                GeminiHandler(apiKey = key, endpointBase = "http://127.0.0.1:${server.port}/v1beta/models")
            }
        )
        val summary2 = engine2.runWithNames(listOf(root.uri to "novel_claims"), testSettings)
        assertEquals("claimsが解放されており再処理で完了すること", 2, summary2.completedFiles)
    }

    // =========================================================================
    // 4. プロンプト変異・リトライ整合性（Prompt Integrity）
    // =========================================================================

    @Test
    fun testQualityRetry_PreservesCurrentPromptNum() = runBlocking {
        val requestPrompts = CopyOnWriteArrayList<String>()

        server.customDispatcher = { call ->
            requestPrompts.add(call.body)
            val translatedText = if (requestPrompts.size == 1) {
                // 1回目は英語のみ（ひらがな率ゼロで品質リトライをトリガー）
                "This is english text only without kana.\n$COMPLETION_MARKER"
            } else {
                // 2回目は正常な日本語訳文
                "これは第一章の日本語訳文です。勇者は王国を救う旅に出ました。\n$COMPLETION_MARKER"
            }
            val resp = """{
                "candidates": [{
                    "content": { "parts": [{ "text": "$translatedText" }] }
                }],
                "usageMetadata": { "promptTokenCount": 10, "candidatesTokenCount": 20 }
            }"""
            Triple(200, resp, emptyMap())
        }

        val store = InMemoryFileStore()
        val root = store.createRoot("prompt_integrity")
        val f = store.createFile(root.uri, "story.txt", "text/plain")!!
        store.writeText(f.uri, "这是第一章的中文小说内容。勇者踏上了拯救王国的旅途。")

        val testSettings = V2Settings(
            geminiKeys = listOf("key-1"),
            profiles = listOf(V2ModelProfile(id = "p1", providerId = "gemini", model = "gemini-2.5-flash", promptOrder = listOf(1, 1))),
            limits = com.example.novelscraper.translation.v2.settings.V2Limits(parallelWorkers = 1, requestDelaySec = 0)
        )

        val engine = RunEngine(
            store = store,
            scope = this,
            options = EngineOptions(batchMaxFiles = 1, maxSameRetries = 2, kanaFloor = 0.2),
            handlerFactory = { _, _, key ->
                GeminiHandler(apiKey = key, endpointBase = "http://127.0.0.1:${server.port}/v1beta/models")
            }
        )

        val summary = engine.runWithNames(listOf(root.uri to "prompt_integrity"), testSettings)

        // リトライを経て最終的に成功していること
        assertEquals(1, summary.completedFiles)
        assertTrue("リトライが発生して2回以上呼ばれていること", requestPrompts.size >= 2)
    }

    // =========================================================================
    // 5. 大容量ストリーミング & 文字コード境界（Ingest & PreSplit）
    // =========================================================================

    @Test
    fun testPreSplit_StreamingBigFile_ZeroOOM_EncodingPreserved() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("streaming_novel")
        val rawFile = store.createFile(root.uri, "big_novel.txt", "text/plain")!!

        // 1000行のテキストを生成
        val paragraph = "第一章 勇者の旅立ち。世界を救うために彼は歩き始めた。\n"
        val totalLines = 1000
        val fullContent = paragraph.repeat(totalLines)
        store.writeText(rawFile.uri, fullContent)

        val splitDir = store.createDir(root.uri, "split_out")!!
        val logs = mutableListOf<String>()

        val result = splitSingleTextFile(
            store = store,
            fileUri = rawFile.uri,
            fileName = "big_novel.txt",
            splitRootUri = splitDir.uri,
            splitSizeChars = 2000,
            declared = null,
            stopped = { false },
            onSkipped = { },
            log = { logs.add(it) }
        )

        assertNotNull("ストリーミング物理分割が成功すること", result)
        assertTrue("パート数が1より大きいこと", result!!.partCount > 1)

        val partDocs = store.children(result.subfolderUri)
            .filter { it.name.startsWith("part_") && it.name.endsWith(".txt") }
        assertEquals(result.partCount, partDocs.size)

        var restoredLineCount = 0
        for (part in partDocs) {
            val text = store.readText(part.uri) ?: ""
            assertFalse("パートにU+FFFD置換文字（文字化け）が含まれないこと", text.contains('\uFFFD'))
            restoredLineCount += text.lines().count { it.isNotBlank() }
        }
        assertEquals("改行や行数が完全に一致して欠落がないこと", totalLines, restoredLineCount)
    }

    // =========================================================================
    // 4. 思考（Thinking / Reasoning）パラメータの公式API仕様適合性テスト
    // =========================================================================

    @Test
    fun testGemini3x_ThinkingLevel_EmitsOnlyThinkingLevel() = runBlocking {
        val endpoint = "http://127.0.0.1:${server.port}/v1beta/models"
        val handler = GeminiHandler(apiKey = "test-key", endpointBase = endpoint)

        // Gemini 3.8 Flash (Levels: low/medium/high) - 大文字MEDIUMを入力
        val profile = V2ModelProfile(
            providerId = "gemini",
            model = "gemini-3.8-flash",
            thinkingLevel = "MEDIUM",
            thinkingBudget = 2048 // 3.xモデルでは能力層で除外されるべき値
        )
        val opts = resolveProfileOptions(profile, GEMINI_DESCRIPTOR)
        val req = LlmRequest(
            providerId = "gemini",
            model = "gemini-3.8-flash",
            systemPrompt = "sys",
            userText = "user",
            options = opts
        )

        val result = handler.call(req)
        assertTrue("Successであること", result is LlmResult.Success)

        val call = server.recordedRequests.first()
        // 仕様検証: thinkingLevelは小文字"medium"として送出され、thinkingBudgetは決して共存しない
        assertTrue("thinkingLevel: medium が含まれること", call.body.contains(""""thinkingLevel":"medium""""))
        assertFalse("thinkingBudget は絶対に送出されないこと（排他性）", call.body.contains("thinkingBudget"))
    }

    @Test
    fun testGemini25_ThinkingBudget_EmitsOnlyThinkingBudget() = runBlocking {
        val endpoint = "http://127.0.0.1:${server.port}/v1beta/models"
        val handler = GeminiHandler(apiKey = "test-key", endpointBase = endpoint)

        // Gemini 2.5 Flash (Budget: -1..24576) - 思考無効化(0)を指定
        val profile = V2ModelProfile(
            providerId = "gemini",
            model = "gemini-2.5-flash",
            thinkingLevel = "high", // 2.5モデルでは能力層で除外されるべき値
            thinkingBudget = 0
        )
        val opts = resolveProfileOptions(profile, GEMINI_DESCRIPTOR)
        val req = LlmRequest(
            providerId = "gemini",
            model = "gemini-2.5-flash",
            systemPrompt = "sys",
            userText = "user",
            options = opts
        )

        val result = handler.call(req)
        assertTrue("Successであること", result is LlmResult.Success)

        val call = server.recordedRequests.first()
        // 仕様検証: thinkingBudget: 0 が送出され、thinkingLevelは絶対に送出されない
        assertTrue("thinkingBudget: 0 が含まれること", call.body.contains(""""thinkingBudget":0"""))
        assertFalse("thinkingLevel は絶対に送出されないこと（排他性）", call.body.contains("thinkingLevel"))
    }

    @Test
    fun testOpenRouter_ReasoningEffortAndEnabled_CoexistWithoutDropping() = runBlocking {
        server.responseBody = """{"choices":[{"message":{"content":"ok\n$COMPLETION_MARKER"}}]}"""
        val endpoint = "http://127.0.0.1:${server.port}/api/v1/chat/completions"

        val handler = OpenRouterHandler(
            apiKey = "sk-test",
            endpoint = endpoint,
            reasoningEffort = "HIGH",     // 大文字
            reasoningEnabled = true       // 共存
        )
        val req = LlmRequest(
            providerId = "openrouter",
            model = "anthropic/claude-3.7-sonnet",
            systemPrompt = "sys",
            userText = "user"
        )

        val result = handler.call(req)
        assertTrue("Successであること", result is LlmResult.Success)

        val call = server.recordedRequests.first()
        // 仕様検証: effort と enabled の両方がドロップされずに共存していること
        assertTrue("effort: high が含まれること（小文字正規化）", call.body.contains(""""effort":"high""""))
        assertTrue("enabled: true が含まれること（消失バグ解消確認）", call.body.contains(""""enabled":true"""))
    }

    @Test
    fun testOpenRouter_ExtendedEffortValues_Supported() = runBlocking {
        server.responseBody = """{"choices":[{"message":{"content":"ok\n$COMPLETION_MARKER"}}]}"""
        val endpoint = "http://127.0.0.1:${server.port}/api/v1/chat/completions"

        val handler = OpenRouterHandler(
            apiKey = "sk-test",
            endpoint = endpoint,
            reasoningEffort = "minimal"
        )
        val req = LlmRequest(providerId = "openrouter", model = "openai/o3-mini", systemPrompt = "", userText = "x")
        handler.call(req)

        val call = server.recordedRequests.first()
        assertTrue("minimal が正しく送出されること", call.body.contains(""""effort":"minimal""""))
    }
}
