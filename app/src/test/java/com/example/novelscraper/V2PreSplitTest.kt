package com.example.novelscraper

import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.pipeline.PRE_SPLIT_MIN_CHARS
import com.example.novelscraper.translation.v2.pipeline.PRE_SPLIT_SAMPLE_CHARS
import com.example.novelscraper.translation.v2.pipeline.cleanseForSplit
import com.example.novelscraper.translation.v2.pipeline.splitSingleTextFile
import com.example.novelscraper.translation.v2.pipeline.splitTextToParts
import org.junit.Assert.*
import org.junit.Test

class V2PreSplitTest {

    @Test
    fun testCleanseForSplit() {
        assertEquals("abc", cleanseForSplit("a\u0000b\uFEFFc"))
        assertEquals("a\nb\tc", cleanseForSplit("a\nb\tc"))
        assertEquals("plain", cleanseForSplit("plain"))
    }

    @Test
    fun testSplitTextToParts_Bundling() {
        val line = "勇者は旅に出た。\n"
        val text = line.repeat(150)
        val parts = splitTextToParts(text, 1000)
        assertTrue(parts.size > 1)
        // No content loss: non-blank lines preserved in order.
        val lines = parts.flatMap { it.lines() }.filter { it.isNotBlank() }
        assertEquals(150, lines.size)
        assertTrue(lines.all { it == "勇者は旅に出た。" })
    }

    @Test
    fun testSplitTextToParts_OversizedLine() {
        val long = "あ".repeat(3000)
        val parts = splitTextToParts("head\n$long\ntail\n", 1000)
        assertTrue(parts.size > 1)
        assertEquals("head", parts.flatMap { it.lines() }.first { it.isNotBlank() })
        assertEquals("tail", parts.flatMap { it.lines() }.last { it.isNotBlank() })
    }

    @Test
    fun testSplitTextToParts_MinClamp() {
        // Below-minimum budgets are clamped to PRE_SPLIT_MIN_CHARS:
        // 400 chars stay one part (unclamped size 10 would machine-split them).
        val text = "あ".repeat(400)
        assertEquals(1, splitTextToParts(text, 10).size)
        assertTrue(PRE_SPLIT_MIN_CHARS > 10)
    }

    @Test
    fun testSplitFile_EndToEnd() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val raw = store.createFile(root.uri, "story.txt", "text/plain")!!
        val body = "昔々あるところに勇者がいました。\n".repeat(120)
        store.writeText(raw.uri, body)
        val splitRoot = store.createDir(root.uri, "split-out")!!

        val logs = mutableListOf<String>()
        val result = splitSingleTextFile(
            store, raw.uri, "story.txt", splitRoot.uri, 1000
        ) { logs.add(it) }
        assertNotNull(result)
        assertTrue(logs.none { it.contains("skip") || it.contains("fail") || it.contains("error") })
        assertEquals("story", result!!.novelName)
        assertTrue(result.partCount > 1)
        assertTrue(result.sampleText.contains("勇者"))

        val children = store.children(result.subfolderUri).map { it.name }.sorted()
        assertEquals(result.partCount, children.size)
        assertTrue(children.all { it.startsWith("part_") && it.endsWith(".txt") })
        // Round trip: parts rejoin to the cleansed source lines.
        val rejoined = children.map { store.readText(store.findChild(result.subfolderUri, it)!!.uri) }.joinToString("")
        assertEquals(
            body.lines().filter { it.isNotBlank() },
            rejoined.lines().filter { it.isNotBlank() }
        )
    }

    @Test
    fun testSplitFile_SampleIsHeadExcerpt() = kotlinx.coroutines.runBlocking {
        // sampleは言語判定専用の先頭抜粋（新規・再開の両経路で一致させる）
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val raw = store.createFile(root.uri, "long.txt", "text/plain")!!
        val body = "先頭マーカー\n" + "あ".repeat(20000)
        store.writeText(raw.uri, body)
        val splitRoot = store.createDir(root.uri, "split-out")!!

        val result = splitSingleTextFile(
            store, raw.uri, "long.txt", splitRoot.uri, 1000
        ) {}
        assertNotNull(result)
        assertEquals(body.take(PRE_SPLIT_SAMPLE_CHARS), result!!.sampleText)
        assertTrue(result.sampleText.length <= PRE_SPLIT_SAMPLE_CHARS)
    }

    @Test
    fun testSplitFile_QuarantinedSkips() = kotlinx.coroutines.runBlocking {
        // 文字化け確定は null＋通知（翻訳対象外）。通常経路へのすり抜けは RunEngine 側で遮断する。
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val raw = store.createFile(root.uri, "bad.txt", "text/plain")!!
        val pattern = byteArrayOf(
            0x00.toByte(), 0x98.toByte(), 0x81.toByte(),
            0x8D.toByte(), 0xFF.toByte(), 0x80.toByte()
        )
        store.writeBytes(raw.uri, ByteArray(2000) { i -> pattern[i % pattern.size] })
        val splitRoot = store.createDir(root.uri, "split-out")!!

        val skipped = mutableListOf<String>()
        val result = splitSingleTextFile(
            store, raw.uri, "bad.txt", splitRoot.uri, 1000,
            onSkipped = { skipped.add(it) }
        ) {}
        assertNull(result)
        assertEquals(1, skipped.size)
    }

    @Test
    fun testSplitFile_ExistingRespected() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val raw = store.createFile(root.uri, "story.txt", "text/plain")!!
        store.writeText(raw.uri, "本文です。\n".repeat(200))
        val splitRoot = store.createDir(root.uri, "split-out")!!

        val first = splitSingleTextFile(store, raw.uri, "story.txt", splitRoot.uri, 1000) {}
        assertNotNull(first)
        var logged = false
        val second = splitSingleTextFile(store, raw.uri, "story.txt", splitRoot.uri, 1000) {
            if (it.contains("already split")) logged = true
        }
        assertNotNull(second)
        assertEquals(first!!.subfolderUri, second!!.subfolderUri)
        assertTrue(logged)
    }

    @Test
    fun testSplitFile_EmptyYieldsOnePart() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val raw = store.createFile(root.uri, "empty.txt", "text/plain")!!
        store.writeText(raw.uri, "")
        val splitRoot = store.createDir(root.uri, "split-out")!!

        val result = splitSingleTextFile(store, raw.uri, "empty.txt", splitRoot.uri, 1000) {}
        assertNotNull(result)
        assertEquals(1, result!!.partCount)
    }

    @Test
    fun testSplitFile_MojibakeAborts() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        // FFFD flood inside an SJIS-claimed payload trips the verifier.
        val bad = "あ".repeat(60) + 0xFFFD.toChar().toString().repeat(10) + "あ".repeat(60)
        val raw = store.createFile(root.uri, "bad.txt", "text/plain")!!
        store.writeText(raw.uri, bad)
        val splitRoot = store.createDir(root.uri, "split-out")!!

        var logged = false
        val result = splitSingleTextFile(store, raw.uri, "bad.txt", splitRoot.uri, 10000) {
            if (it.contains("mojibake")) logged = true
        }
        assertNull(result)
        assertTrue(logged)
        // Incomplete work is rolled back.
        assertNull(store.findChild(splitRoot.uri, "bad"))
    }

    @Test
    fun testSplitFile_SjisBytes() = kotlinx.coroutines.runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novel")
        val text = "勇者は旅に出ました。\n".repeat(150)
        val raw = store.createFile(root.uri, "sjis.txt", "text/plain")!!
        assertTrue(store.writeBytes(raw.uri, text.toByteArray(java.nio.charset.Charset.forName("Windows-31J"))))
        val splitRoot = store.createDir(root.uri, "split-out")!!

        val result = splitSingleTextFile(store, raw.uri, "sjis.txt", splitRoot.uri, 1000) {}
        assertNotNull(result)
        val first = store.readText(store.findChild(result!!.subfolderUri, "part_0001.txt")!!.uri) ?: ""
        assertTrue(first.contains("勇者"))
    }

    @Test
    fun testTrimIncompleteMultibyte_WithNewline() {
        val base = "あ".repeat(1000) + "\n" + "い".repeat(10)
        val bytes = base.toByteArray(Charsets.UTF_8)
        // 末尾1文字を途中で切断
        val truncated = bytes.copyOf(bytes.size - 1)
        val trimmed = com.example.novelscraper.translation.v2.pipeline.trimIncompleteMultibyte(truncated)
        // 改行で綺麗にトリムされていることを確認
        val text = String(trimmed, Charsets.UTF_8)
        assertTrue(text.endsWith("\n"))
        assertEquals("あ".repeat(1000) + "\n", text)
    }

    @Test
    fun testTrimIncompleteMultibyte_WithoutNewlineUtf8() {
        val base = "あいうえお"
        val bytes = base.toByteArray(Charsets.UTF_8) // 15 bytes (3 bytes each)
        // 最後の「お」の3バイト目だけ切り落とす (14 bytes)
        val truncated = bytes.copyOf(14)
        val trimmed = com.example.novelscraper.translation.v2.pipeline.trimIncompleteMultibyte(truncated)
        // 「お」全体が安全に切り落とされ、「あいうえ」の12バイトになることを確認
        assertEquals(12, trimmed.size)
        assertEquals("あいうえ", String(trimmed, Charsets.UTF_8))
    }
}
