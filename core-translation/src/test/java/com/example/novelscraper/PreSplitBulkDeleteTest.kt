package com.example.novelscraper

import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.PRE_SPLIT_MANIFEST_NAME
import com.example.novelscraper.translation.v2.pipeline.isBulkDeleteSafe
import com.example.novelscraper.translation.v2.pipeline.rollbackNewDirFast
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreSplitBulkDeleteTest {

    private fun doc(name: String, isDir: Boolean = false) = VDoc("mem://x/$name", name, isDir)

    @Test
    fun guard_acceptsOnlySplitArtifacts() {
        assertTrue(isBulkDeleteSafe(emptyList()))
        assertTrue(
            isBulkDeleteSafe(
                listOf(
                    doc("part_0001.txt"),
                    doc("part_0002.txt"),
                    doc(PRE_SPLIT_MANIFEST_NAME)
                )
            )
        )
    }

    @Test
    fun guard_rejectsIntruders() {
        // 利用者ファイル・中間物・サブフォルダのいずれも拒否する
        assertFalse(isBulkDeleteSafe(listOf(doc("part_0001.txt"), doc("memo.txt"))))
        assertFalse(isBulkDeleteSafe(listOf(doc("part_0001.txt"), doc(".tmp_x"))))
        assertFalse(isBulkDeleteSafe(listOf(doc("part_0001.txt"), doc("sub", isDir = true))))
        assertFalse(isBulkDeleteSafe(listOf(doc("dictionary.json"))))
    }

    @Test
    fun deleteDirectory_removesSubtreeOnly() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("r")
        val splitRoot = store.createDir(root.uri, "split-out")!!
        val novel = store.createDir(splitRoot.uri, "story")!!
        val keep = store.createDir(splitRoot.uri, "other")!!
        val part = store.createFile(novel.uri, "part_0001.txt", "text/plain")!!
        assertTrue(store.writeText(part.uri, "本文"))

        assertTrue(store.deleteDirectory(novel.uri))
        assertNull(store.findChild(splitRoot.uri, "story"))
        // 隣は無傷であること
        assertNotNull(store.findChild(splitRoot.uri, "other"))
        assertNotNull(keep)
    }

    @Test
    fun deleteDirectory_rejectsMissingAndFile() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("r")
        assertFalse(store.deleteDirectory("${root.uri}/nope"))
        val file = store.createFile(root.uri, "a.txt", "text/plain")!!
        assertFalse(store.deleteDirectory(file.uri))
        assertNotNull(store.findChild(root.uri, "a.txt"))
    }

    @Test
    fun fastRollback_succeedsOnCleanDir() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("r")
        val splitRoot = store.createDir(root.uri, "split-out")!!
        val novel = store.createDir(splitRoot.uri, "story")!!
        val part = store.createFile(novel.uri, "part_0001.txt", "text/plain")!!
        assertTrue(store.writeText(part.uri, "本文"))
        val manifest = store.createFile(novel.uri, PRE_SPLIT_MANIFEST_NAME, "application/json")!!
        assertTrue(store.writeText(manifest.uri, "{}"))

        val logs = mutableListOf<String>()
        assertTrue(rollbackNewDirFast(store, splitRoot.uri, "story", novel.uri) { logs.add(it) })
        assertNull(store.findChild(splitRoot.uri, "story"))
        assertTrue(logs.none { it.contains("個別削除") })
    }

    @Test
    fun fastRollback_fallsBackOnIntruder() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("r")
        val splitRoot = store.createDir(root.uri, "split-out")!!
        val novel = store.createDir(splitRoot.uri, "story")!!
        val part = store.createFile(novel.uri, "part_0001.txt", "text/plain")!!
        assertTrue(store.writeText(part.uri, "本文"))
        val intruder = store.createFile(novel.uri, "memo.txt", "text/plain")!!
        assertTrue(store.writeText(intruder.uri, "利用者のメモ"))

        val logs = mutableListOf<String>()
        assertFalse(rollbackNewDirFast(store, splitRoot.uri, "story", novel.uri) { logs.add(it) })
        // 個別削除への退行に備えて中身は無傷であること
        assertNotNull(store.findChild(splitRoot.uri, "story"))
        assertNotNull(store.findChild(novel.uri, "memo.txt"))
        assertNotNull(store.findChild(novel.uri, "part_0001.txt"))
        assertTrue(logs.any { it.contains("個別削除") })
    }
}
