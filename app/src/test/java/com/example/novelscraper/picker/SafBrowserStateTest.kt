package com.example.novelscraper.picker

import com.example.novelscraper.translation.picker.PickerKind
import com.example.novelscraper.translation.picker.SafBrowserState
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SafBrowserStateTest {

    private suspend fun buildStore(): InMemoryFileStore {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        val dirA = store.createDir(root.uri, "A")!!
        val a1 = store.createFile(dirA.uri, "a1.txt", "text/plain")!!
        store.writeText(a1.uri, "本文1")
        val note = store.createFile(dirA.uri, "note.md", "text/plain")!!
        store.writeText(note.uri, "memo")
        val dirB = store.createDir(dirA.uri, "B")!!
        val b1 = store.createFile(dirB.uri, "b1.txt", "text/plain")!!
        store.writeText(b1.uri, "本文2")
        val out = store.createDir(root.uri, "翻訳完了_LLM")!!
        val old = store.createFile(out.uri, "old.txt", "text/plain")!!
        store.writeText(old.uri, "旧訳")
        val tmp = store.createFile(dirA.uri, ".tmp_x", "text/plain")!!
        store.writeText(tmp.uri, "tmp")
        return store
    }

    private fun CoroutineScope.state(store: InMemoryFileStore): SafBrowserState {
        return SafBrowserState(store, this, "mem://novels", "novels")
    }

    private suspend fun SafBrowserState.awaitLoaded() {
        withTimeout(5000) {
            while (loading.value) delay(10)
        }
    }

    @Test
    fun browse_listsSortedDirsFirst() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        val names = state.rows.value.map { it.doc.name }
        assertEquals(listOf("A", "翻訳完了_LLM"), names)
    }

    @Test
    fun browse_txtOnlyHidesOthers() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.enter(state.rows.value.first { it.doc.name == "A" })
        state.awaitLoaded()
        val names = state.rows.value.map { it.doc.name }
        assertTrue(names.contains("a1.txt"))
        assertTrue(names.contains("B"))
        assertFalse(names.contains("note.md"))
        assertFalse(names.contains(".tmp_x"))

        state.setTxtOnly(false)
        state.awaitLoaded()
        val all = state.rows.value.map { it.doc.name }
        assertTrue(all.contains("note.md"))
    }

    @Test
    fun collect_checkedFolderYieldsFolderAndTxtFiles() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.enter(state.rows.value.first { it.doc.name == "A" })
        state.awaitLoaded()
        // Bフォルダとa1.txtをチェック。
        state.toggle(state.rows.value.first { it.doc.name == "B" })
        state.setTxtOnly(false)
        state.awaitLoaded()
        state.toggle(state.rows.value.first { it.doc.name == "a1.txt" })

        val r = state.collectTargets()
        assertFalse(r.truncated)
        assertTrue(r.skipped.isEmpty())
        val folders = r.targets.filter { it.kind == PickerKind.FOLDER }
        val files = r.targets.filter { it.kind == PickerKind.FILE }
        assertEquals(1, folders.size)
        assertEquals("B", folders.single().displayName)
        // b1.txt(親=B) + a1.txt(親=A)。note.mdはtxtOnly=falseのため対象外(collectは.txtのみ)。
        assertEquals(setOf("b1.txt", "a1.txt"), files.map { it.displayName }.toSet())
        val b1 = files.first { it.displayName == "b1.txt" }
        assertTrue(b1.parentDocUri.endsWith("/B"))
        assertEquals("A/a1.txt", files.first { it.displayName == "a1.txt" }.relPath)
    }

    @Test
    fun collect_excludesOutputDir() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.toggle(state.rows.value.first { it.doc.name == "翻訳完了_LLM" })

        val r = state.collectTargets()
        assertTrue(r.targets.isEmpty())
        assertEquals(0, r.fileCount)
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test
    fun collect_keepsChecksAcrossNavigation() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.enter(state.rows.value.first { it.doc.name == "A" })
        state.awaitLoaded()
        // A内でBをチェックしたまま上に抜けても確定時に残る(黙って消さない)。
        state.toggle(state.rows.value.first { it.doc.name == "B" })
        assertTrue(state.goUp())
        state.awaitLoaded()

        val r = state.collectTargets()
        assertTrue(r.skipped.isEmpty())
        assertEquals(1, r.targets.count { it.kind == PickerKind.FOLDER })
        assertEquals("B", r.targets.first { it.kind == PickerKind.FOLDER }.displayName)
        assertEquals(setOf("b1.txt"), r.targets.filter { it.kind == PickerKind.FILE }.map { it.displayName }.toSet())
    }

    @Test
    fun collect_excludesCustomOutputDir() = runBlocking {
        val store = buildStore()
        val custom = store.createDir("mem://novels", "翻訳完了_CUSTOM")!!
        val x = store.createFile(custom.uri, "x.txt", "text/plain")!!
        store.writeText(x.uri, "訳文")
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        state.toggle(state.rows.value.first { it.doc.name == "翻訳完了_CUSTOM" })

        val r = state.collectTargets()
        assertTrue(r.targets.isEmpty())
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test
    fun collect_targetsInRelPathOrder() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        // 作成順をわざと逆順にし、確定順が相対パス順であることを確かめる。
        for (name in listOf("z.txt", "m.txt", "a.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        for (row in state.rows.value.filter { !it.doc.isDirectory }.reversed()) {
            state.toggle(row)
        }

        val r = state.collectTargets()
        assertEquals(
            listOf("a.txt", "m.txt", "z.txt"),
            r.targets.map { it.displayName }
        )
    }

    @Test
    fun collect_goUpAndCrumbs() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        assertFalse(state.goUp())
        state.enter(state.rows.value.first { it.doc.name == "A" })
        state.awaitLoaded()
        assertEquals(2, state.crumbs.value.size)
        assertTrue(state.goUp())
        state.awaitLoaded()
        assertEquals(1, state.crumbs.value.size)
        assertEquals(listOf("A", "翻訳完了_LLM"), state.rows.value.map { it.doc.name })
    }
}
