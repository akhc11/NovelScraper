package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.ChunkEntry
import com.example.novelscraper.translation.v2.pipeline.ChunkManifest
import com.example.novelscraper.translation.v2.pipeline.LargeOptions
import com.example.novelscraper.translation.v2.pipeline.LargeOutcome
import com.example.novelscraper.translation.v2.pipeline.PromptSpec
import com.example.novelscraper.translation.v2.pipeline.TranslateContext
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.pipeline.prepareChunkSession
import com.example.novelscraper.translation.v2.pipeline.readChunkManifest
import com.example.novelscraper.translation.v2.pipeline.sha256Hex
import com.example.novelscraper.translation.v2.pipeline.splitIntoChunks
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.writeChunkManifest
import com.example.novelscraper.translation.v2.pipeline.writeChunks
import org.junit.Assert.*
import org.junit.Test

/**
 * 想定外を拾う層：故障注入＋不変条件・同値性の検査。
 * 例示テストが「想定の再確認」にすぎない反省から、故障モデルは実機プロバイダの
 * 文書化された振る舞い（古い一覧・表示名改変・短縮書込・置換非対応）から作る。
 * 新規依存なし。乱数は固定シードで再現可能。
 *
 * 不変条件：
 * - 最終成果物は「不在」か「新規実行と同一」かのいずれか（欠落公開の禁止）
 * - 故障停止＋再開の結果は新規実行と同一（再開同値性）
 * - 不正な宣言書は例外なく作り直し（fail-closed）
 */
class V2ChunkResilienceTest {

    private fun ok(text: String) = LlmResult.Success(text = text)

    private fun strictCtx(
        call: suspend (String, PromptSpec, String) -> LlmResult,
        stopped: () -> Boolean = { false }
    ) = TranslateContext(
        basePrompts = mapOf(1 to "base"),
        promptOrder = listOf(1),
        driverNames = listOf("d1"),
        verify = VerifyOptions(sizeMinPct = 0, sizeMaxPct = 10000, kanaFloor = 0.0, markerEnabled = false),
        call = call,
        stopped = stopped
    )

    /**
     * 故障注入保存層。欠落・改変・短縮・非対応だけを起こす（内容の捏造はしない）。
     * 読み返し破損（bit-rot級）は対象外。対象外の理由：単一書込者の前提では書込時照合で閉じるため。
     */
    private class FaultStore(
        val inner: InMemoryFileStore = InMemoryFileStore(),
        seed: Long = 0L,
        var faultRate: Double = 0.0
    ) : FileStore by inner {
        private val rng = kotlin.random.Random(seed)
        private fun trip() = rng.nextDouble() < faultRate

        override suspend fun findChild(dirUri: String, name: String): VDoc? {
            if (trip()) return null // 古い一覧
            return inner.findChild(dirUri, name)
        }

        override suspend fun children(dirUri: String): List<VDoc> {
            val all = inner.children(dirUri)
            if (!trip()) return all
            return all.filter { rng.nextDouble() >= faultRate } // 古い一覧（欠落のみ）
        }

        override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? {
            if (trip()) return null
            return inner.createFile(dirUri, name, mime)
        }

        override suspend fun writeText(fileUri: String, content: String): Boolean {
            if (trip() && content.length > 1) {
                return inner.writeText(fileUri, content.substring(0, content.length / 2)) // 短縮書込
            }
            return inner.writeText(fileUri, content)
        }

        override suspend fun renameFile(dirUri: String, fileUri: String, newName: String): VDoc? {
            if (trip()) return null // 置換非対応
            return inner.renameFile(dirUri, fileUri, newName)
        }

        override suspend fun deleteFile(fileUri: String): Boolean {
            if (trip()) return false
            return inner.deleteFile(fileUri)
        }
    }

    /** 表示名改変の確定的再現：塊・別名に拡張子を付与する（宣言書自体は不変）。 */
    private class AlterStore(val inner: InMemoryFileStore = InMemoryFileStore()) : FileStore by inner {
        override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? {
            val altered = if (name.startsWith("chunk_") || name.startsWith(".tmp_")) "$name.txt" else name
            return inner.createFile(dirUri, altered, mime)
        }
    }

    /**
     * 生成応答と一覧の乖離の再現：作成時は要求名を返答するが、実体は改変名で置かれる。
     * 置換は非対応にし、退行経路も使う。
     */
    private class AlterEchoStore(val inner: InMemoryFileStore = InMemoryFileStore()) : FileStore by inner {
        override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? {
            val altered = if (name.startsWith("chunk_") || name.startsWith(".tmp_")) "$name.txt" else name
            val created = inner.createFile(dirUri, altered, mime) ?: return null
            return created.copy(name = name)
        }

        override suspend fun renameFile(dirUri: String, fileUri: String, newName: String): VDoc? = null
    }

    @Test
    fun testResilience_ResumeEqualsFreshUnderFaults() = kotlinx.coroutines.runBlocking {
        val content = (1..40).joinToString("\n") { "行$it の本文です。" }
        val options = LargeOptions(chunkSizeBytes = 300)

        // 参照：素の層で新規実行
        val freshStore = InMemoryFileStore()
        val freshRoot = freshStore.createRoot("fresh")
        val freshWork = freshStore.createDir(freshRoot.uri, ".parts_f")!!
        assertTrue(translateLarge(freshStore, freshWork.uri, freshRoot.uri, "f.txt", content, strictCtx({ _, _, s -> ok("訳:$s") }), options) is LargeOutcome.Completed)
        val freshFinal = freshStore.readText(freshStore.findChild(freshRoot.uri, "f.txt")!!.uri)!!

        // 故障＋中断：欠落公開がなければ何が起きてもよい
        val fault = FaultStore(seed = 7L, faultRate = 0.25)
        val root = fault.inner.createRoot("faulty")
        val work = fault.inner.createDir(root.uri, ".parts_f")!!
        var calls = 0
        val stopAfter = 4
        val r1 = translateLarge(
            fault, work.uri, root.uri, "f.txt", content,
            strictCtx(
                call = { _, _, s -> calls++; ok("訳:$s") },
                stopped = { calls >= stopAfter }
            ),
            options
        )
        val partial = fault.inner.findChild(root.uri, "f.txt")?.let { fault.inner.readText(it.uri) }
        assertTrue("欠落公開の禁止", partial == null || partial == freshFinal)
        // 中断したなら停止で終わるはず（停止が発動した証拠）
        if (calls >= stopAfter) assertTrue(r1 is LargeOutcome.Stopped)

        // 収束：故障停止＋再開で新規実行と同一になること
        fault.faultRate = 0.0
        var calls2 = 0
        val r2 = translateLarge(
            fault, work.uri, root.uri, "f.txt", content,
            strictCtx(call = { _, _, s -> calls2++; ok("訳:$s") }),
            options
        )
        assertTrue(r2 is LargeOutcome.Completed)
        assertEquals(freshFinal, fault.inner.readText(fault.inner.findChild(root.uri, "f.txt")!!.uri))
        assertNull(fault.inner.findChild(root.uri, ".parts_f"))
    }

    @Test
    fun testResilience_MalformedManifest_FailClosedToFresh() = kotlinx.coroutines.runBlocking {
        val content = "お話です。\n".repeat(20)
        val expectedNames = splitIntoChunks(content, 300).indices.map { "chunk_" + String.format("%04d", it + 1) }
        val bad = listOf(
            "", "{", "[]", "null",
            "{\"version\":99,\"sourceHash\":\"x\",\"chunkSizeBytes\":300,\"chunks\":[\"a\"]}",
            "{\"version\":1}",
            "{\"version\":1,\"sourceHash\":\"\",\"chunkSizeBytes\":300,\"chunks\":[]}",
            "x".repeat(100000)
        )
        for ((i, v) in bad.withIndex()) {
            val store = InMemoryFileStore()
            val root = store.createRoot("fuzz$i")
            val work = store.createDir(root.uri, ".parts_f")!!
            val broken = store.createFile(work.uri, "manifest.json", "application/json")!!
            store.writeText(broken.uri, v)
            // 例外なく新規分割に落ちること
            val names = prepareChunkSession(store, work.uri, content, 300, {})?.names ?: emptyList()
            assertEquals("fuzz[$i]", expectedNames, names)
            // 自己修復：有効な宣言書が確定していること
            assertNotNull("fuzz[$i]", readChunkManifest(store, work.uri))
        }
    }

    /** 作成直後の一覧未反映の再現：入出力場所の名寄せが常に空振りする。 */
    private class BlindDirsStore(val inner: InMemoryFileStore = InMemoryFileStore()) : FileStore by inner {
        override suspend fun findChild(dirUri: String, name: String): VDoc? {
            if (name == "in" || name == "out") return null
            return inner.findChild(dirUri, name)
        }
    }

    @Test
    fun testResilience_HoldCarriesReason() = kotlinx.coroutines.runBlocking {
        // 保持には理由と進捗が載り、ログが「何・なぜ・どこまで」を残せること
        val store = InMemoryFileStore()
        val root = store.createRoot("hold_reason")
        val work = store.createDir(root.uri, ".parts_f")!!
        val outDir = store.createDir(work.uri, "out")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val names = writeChunks(store, store.createDir(work.uri, "in")!!.uri, content, 300, {})
        val parts = splitIntoChunks(content, 300)
        assertEquals(parts.size, names.size)
        val manifest = ChunkManifest(
            2, sha256Hex(content), 300,
            names.zip(parts) { n, p -> ChunkEntry(n, sha256Hex(p)) }
        )
        assertTrue(writeChunkManifest(store, work.uri, manifest))
        val failedDoc = store.createFile(outDir.uri, names[0] + ".failed", "text/plain")!!
        store.writeText(failedDoc.uri, "blocked")
        val r = translateLarge(
            store, work.uri, root.uri, "f.txt", content,
            strictCtx(call = { _, _, s -> ok("訳:$s") }),
            LargeOptions(chunkSizeBytes = 300)
        )
        assertTrue(r is LargeOutcome.Held)
        val held = r as LargeOutcome.Held
        assertTrue(held.reason.isNotBlank())
        assertEquals(names.size, held.totalChunks)
        assertEquals(0, held.completedChunks)
    }

    /** 一覧の一時的な欠落の再現：最初の1回だけ指定名を見せない（読み直しは可）。 */
    private class TransientHideStore(val inner: InMemoryFileStore) : FileStore by inner {
        private var hidesLeft = 1
        override suspend fun children(dirUri: String): List<VDoc> {
            val all = inner.children(dirUri)
            if (hidesLeft > 0) {
                hidesLeft--
                return all.filter { it.name != "chunk_0002" }
            }
            return all
        }
    }

    @Test
    fun testResilience_StaleSubsetKeepsTranslations() = kotlinx.coroutines.runBlocking {
        // 一覧の一時的な欠落で確定済み訳文を捨てず、残りだけ送って同一成果になること
        val inner = InMemoryFileStore()
        val root = inner.createRoot("stale_subset")
        val work = inner.createDir(root.uri, ".parts_f")!!
        val content = "第一のお話です。\n".repeat(20) + "第二のお話です。\n".repeat(20)
        val options = LargeOptions(chunkSizeBytes = 300)

        val freshStore = InMemoryFileStore()
        val freshRoot = freshStore.createRoot("stale_fresh")
        val freshWork = freshStore.createDir(freshRoot.uri, ".parts_f")!!
        assertTrue(translateLarge(freshStore, freshWork.uri, freshRoot.uri, "f.txt", content, strictCtx(call = { _, _, s -> ok("訳:$s") }), options) is LargeOutcome.Completed)
        val freshFinal = freshStore.readText(freshStore.findChild(freshRoot.uri, "f.txt")!!.uri)!!
        val total = splitIntoChunks(content, 300).size
        assertTrue(total >= 2)

        // 1回目：先頭塊だけ訳して中断する
        var calls1 = 0
        val r1 = translateLarge(
            inner, work.uri, root.uri, "f.txt", content,
            strictCtx(
                call = { _, _, s -> calls1++; ok("訳:$s") },
                stopped = { calls1 >= 1 }
            ),
            options
        )
        assertTrue(r1 is LargeOutcome.Stopped)
        assertEquals(1, calls1)

        // 2回目：入側2件目が一瞬見えなくても確定済み訳文を捨てずに完走すること
        val store = TransientHideStore(inner)
        var calls2 = 0
        val r2 = translateLarge(
            store, work.uri, root.uri, "f.txt", content,
            strictCtx(call = { _, _, s -> calls2++; ok("訳:$s") }),
            options
        )
        assertTrue(r2 is LargeOutcome.Completed)
        assertEquals(total - 1, calls2)
        assertEquals(freshFinal, inner.readText(inner.findChild(root.uri, "f.txt")!!.uri))
    }

    @Test
    fun testResilience_BlindSessionDirs_CompletesWithReason() = kotlinx.coroutines.runBlocking {
        // 今回の実機障害そのもの：場所の名寄せが空振りしても、解決済みURIで完走すること
        val blind = BlindDirsStore()
        val root = blind.inner.createRoot("blind_dirs")
        val work = blind.inner.createDir(root.uri, ".parts_f")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val r = translateLarge(
            blind, work.uri, root.uri, "f.txt", content,
            strictCtx(call = { _, _, s -> ok("訳:$s") }),
            LargeOptions(chunkSizeBytes = 300)
        )
        assertTrue(r is LargeOutcome.Completed)
        val finalText = blind.inner.readText(blind.inner.findChild(root.uri, "f.txt")!!.uri)!!
        assertTrue(finalText.contains("行30 の本文です。"))
    }

    @Test
    fun testResilience_EchoDivergence_CompletesIdentically() = kotlinx.coroutines.runBlocking {
        // 生成応答と一覧の乖離でも、成果物は欠落なく同一になること（作り直しは許容、欠落は禁止）
        val echo = AlterEchoStore()
        val root = echo.inner.createRoot("echo")
        val work = echo.inner.createDir(root.uri, ".parts_f")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val options = LargeOptions(chunkSizeBytes = 300)

        val freshStore = InMemoryFileStore()
        val freshRoot = freshStore.createRoot("echo_fresh")
        val freshWork = freshStore.createDir(freshRoot.uri, ".parts_f")!!
        assertTrue(translateLarge(freshStore, freshWork.uri, freshRoot.uri, "f.txt", content, strictCtx(call = { _, _, s -> ok("訳:$s") }), options) is LargeOutcome.Completed)
        val freshFinal = freshStore.readText(freshStore.findChild(freshRoot.uri, "f.txt")!!.uri)!!

        assertTrue(
            translateLarge(
                echo, work.uri, root.uri, "f.txt", content,
                strictCtx(call = { _, _, s -> ok("訳:$s") }), options
            ) is LargeOutcome.Completed
        )
        assertEquals(freshFinal, echo.inner.readText(
            echo.inner.children(root.uri).first { it.name == "f.txt" }.uri
        ))
        // 中断して残った作業所を再開しても同一成果になること
        // （出側の使い回しが効かない分は送り直す。正しさは保つ）
        val work2 = echo.inner.createDir(root.uri, ".parts_g")!!
        var calls1 = 0
        val r1 = translateLarge(
            echo, work2.uri, root.uri, "g.txt", content,
            strictCtx(
                call = { _, _, s -> calls1++; ok("訳:$s") },
                stopped = { calls1 >= 1 }
            ),
            options
        )
        assertTrue(r1 is LargeOutcome.Stopped)
        assertTrue(
            translateLarge(
                echo, work2.uri, root.uri, "g.txt", content,
                strictCtx(call = { _, _, s -> ok("訳:$s") }), options
            ) is LargeOutcome.Completed
        )
        assertEquals(freshFinal, echo.inner.readText(
            echo.inner.children(root.uri).first { it.name == "g.txt" }.uri
        ))
    }

    @Test
    fun testResilience_AlteredNames_ReuseAndConverge() = kotlinx.coroutines.runBlocking {
        val alter = AlterStore()
        val root = alter.inner.createRoot("alter")
        val work = alter.inner.createDir(root.uri, ".parts_f")!!
        val content = (1..30).joinToString("\n") { "行$it の本文です。" }
        val options = LargeOptions(chunkSizeBytes = 300)

        // 参照：素の層で新規実行
        val freshStore = InMemoryFileStore()
        val freshRoot = freshStore.createRoot("alter_fresh")
        val freshWork = freshStore.createDir(freshRoot.uri, ".parts_f")!!
        assertTrue(translateLarge(freshStore, freshWork.uri, freshRoot.uri, "f.txt", content, strictCtx(call = { _, _, s -> ok("訳:$s") }), options) is LargeOutcome.Completed)
        val freshFinal = freshStore.readText(freshStore.findChild(freshRoot.uri, "f.txt")!!.uri)!!

        // 中断して残った作業所（宣言書＋既訳）を再開し、残りだけ送って同一成果になること
        var calls1 = 0
        val r1 = translateLarge(
            alter, work.uri, root.uri, "f.txt", content,
            strictCtx(
                call = { _, _, s -> calls1++; ok("訳:$s") },
                stopped = { calls1 >= 1 }
            ),
            options
        )
        assertTrue(r1 is LargeOutcome.Stopped)
        assertEquals(1, calls1)
        assertNotNull(alter.inner.findChild(work.uri, "manifest.json"))

        var calls2 = 0
        assertTrue(
            translateLarge(
                alter, work.uri, root.uri, "f.txt", content,
                strictCtx(call = { _, _, s -> calls2++; ok("訳:$s") }), options
            ) is LargeOutcome.Completed
        )
        val total = splitIntoChunks(content, 300).size
        assertEquals(total - 1, calls2)
        assertEquals(freshFinal, alter.inner.readText(alter.inner.findChild(root.uri, "f.txt")!!.uri))
        assertNull(alter.inner.findChild(root.uri, ".parts_f"))
    }
}
