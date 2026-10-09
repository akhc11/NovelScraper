package com.example.novelscraper.picker

import com.example.novelscraper.translation.picker.PickerKind
import com.example.novelscraper.translation.picker.SafBrowserState
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    fun collect_checkedFolderIsShallowNoExpansion() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.enter(state.rows.value.first { it.doc.name == "A" })
        state.awaitLoaded()
        // Bフォルダとa1.txtをチェック。フォルダは直下全件意図で展開しない。
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
        // 展開しないためb1.txtは含めない。明示のa1.txtのみ。
        assertEquals(setOf("a1.txt"), files.map { it.displayName }.toSet())
        assertEquals("A/a1.txt", files.single().relPath)
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
        // A内でBをチェックしたまま上に抜けても確定時に残る(黙って消さない)。展開はしない。
        state.toggle(state.rows.value.first { it.doc.name == "B" })
        assertTrue(state.goUp())
        state.awaitLoaded()

        val r = state.collectTargets()
        assertTrue(r.skipped.isEmpty())
        assertEquals(1, r.targets.count { it.kind == PickerKind.FOLDER })
        assertEquals("B", r.targets.first { it.kind == PickerKind.FOLDER }.displayName)
        assertTrue(r.targets.none { it.kind == PickerKind.FILE })
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
    fun sort_nameDescReversesWithinGroups() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.setSortMode(SafBrowserState.SortMode.NAME_DESC)
        state.awaitLoaded()
        // フォルダ優先は不変で群内のみ反転する。
        assertEquals(listOf("翻訳完了_LLM", "A"), state.rows.value.map { it.doc.name })
    }

    @Test
    fun sort_sizeDescPutsLargestFileFirst() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        val dir = store.createDir(root.uri, "D")!!
        for ((name, body) in listOf("s.txt" to "1", "m.txt" to "12345", "l.txt" to "1234567890")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, body)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        state.setSortMode(SafBrowserState.SortMode.SIZE_DESC)
        state.awaitLoaded()
        assertEquals(
            listOf("D", "l.txt", "m.txt", "s.txt"),
            state.rows.value.map { it.doc.name }
        )
    }

    @Test
    fun selectAllVisible_checksAllAndCollects() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.selectAllVisible(state.rows.value)
        assertEquals(2, state.checked.value.size)
        val r = state.collectTargets()
        // 明示集合のみ：Aフォルダ1件。翻訳完了_LLMは除外でskipped入り。孫以下の展開はしない。
        assertEquals(1, r.targets.count { it.kind == PickerKind.FOLDER })
        assertEquals("A", r.targets.single { it.kind == PickerKind.FOLDER }.displayName)
        assertTrue(r.targets.none { it.kind == PickerKind.FILE })
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test
    fun uncheck_removesEntryAndCollectExcludes() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        val a = state.rows.value.first { it.doc.name == "A" }
        val out = state.rows.value.first { it.doc.name == "翻訳完了_LLM" }
        state.toggle(a)
        state.toggle(out)
        assertEquals(2, state.checkedEntries.value.size)
        state.uncheck(out.doc.uri)
        assertEquals(listOf("A"), state.checkedEntries.value.map { it.relPath })
        val r = state.collectTargets()
        assertTrue(r.skipped.isEmpty())
        assertEquals(1, r.targets.count { it.kind == PickerKind.FOLDER })
        assertTrue(r.targets.none { it.kind == PickerKind.FILE })
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

    @Test
    fun range_longPressTwiceSelectsVisibleSlice() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        for (name in listOf("001.txt", "002.txt", "003.txt", "004.txt", "005.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        val visible = state.rows.value.filter { !it.doc.isDirectory }
        assertEquals(
            listOf("001.txt", "002.txt", "003.txt", "004.txt", "005.txt"),
            visible.map { it.doc.name }
        )
        // 長押し1回目は起点設定のみ（選択は増えない）。
        assertEquals(0, state.onLongPressRow(visible, visible[1]))
        assertEquals(0, state.checked.value.size)
        // 長押し2回目で002〜004の区間加算。
        val added = state.onLongPressRow(visible, visible[3])
        assertEquals(3, added)
        val r = state.collectTargets()
        assertEquals(
            listOf("002.txt", "003.txt", "004.txt"),
            r.targets.map { it.displayName }.sorted()
        )
    }

    @Test
    fun range_anchorOutsideViewReanchors() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        for (name in listOf("a.txt", "b.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        val visible = state.rows.value.filter { !it.doc.isDirectory }
        state.setAnchor(visible[0])
        // 表示外の終点では範囲確定せず起点を置き換える。
        val other = SafBrowserState.Row(
            com.example.novelscraper.translation.v2.infra.VDoc("mem://novels/z.txt", "z.txt", false),
            "z.txt"
        )
        assertEquals(0, state.selectRange(visible, other))
        assertEquals(0, state.checked.value.size)
    }

    @Test
    fun sort_naturalOrdersNumericNames() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        for (name in listOf("10.txt", "2.txt", "1.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        assertEquals(
            listOf("1.txt", "2.txt", "10.txt"),
            state.rows.value.map { it.doc.name }
        )
    }

    @Test
    fun rangeSlice_pureHelperOrdersBothDirections() {
        val uris = listOf("a", "b", "c", "d")
        assertEquals(
            listOf("b", "c", "d"),
            SafBrowserState.rangeSlice(uris, "b", "d")
        )
        assertEquals(
            listOf("b", "c", "d"),
            SafBrowserState.rangeSlice(uris, "d", "b")
        )
        assertTrue(SafBrowserState.rangeSlice(uris, null, "b").isEmpty())
        assertTrue(SafBrowserState.rangeSlice(uris, "x", "b").isEmpty())
    }

    @Test
    fun applyQuery_pureHelperFiltersCaseInsensitive() {
        val rows = listOf("001.txt", "002.txt", "Note.MD").map {
            SafBrowserState.Row(
                com.example.novelscraper.translation.v2.infra.VDoc("mem://n/$it", it, false),
                it
            )
        }
        assertEquals(rows, SafBrowserState.applyQuery(rows, "  "))
        assertEquals(
            listOf("001.txt", "002.txt"),
            SafBrowserState.applyQuery(rows, ".TXT").map { it.doc.name }
        )
        assertEquals(
            listOf("002.txt"),
            SafBrowserState.applyQuery(rows, "002").map { it.doc.name }
        )
    }

    @Test
    fun anchor_clearedOnNavigation() = runBlocking {
        val state = state(buildStore())
        state.refresh()
        state.awaitLoaded()
        state.enter(state.rows.value.first { it.doc.name == "A" })
        state.awaitLoaded()
        val b = state.rows.value.first { it.doc.name == "B" }
        state.setAnchor(b)
        assertEquals(b.doc.uri, state.anchorUri.value)
        assertTrue(state.goUp())
        assertNull(state.anchorUri.value)
    }

    @Test
    fun visibleRows_followsQueryAndAnchorName() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        for (name in listOf("001.txt", "002.txt", "003.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        // 技術的根拠1行：Flowは収集起点のためfirstで待ち受け、常駐共有の閉塞を作らない。
        withTimeout(5000) {
            assertEquals(3, state.visibleRows.first { it.size == 3 }.size)
            state.setQuery("002")
            assertEquals("002.txt", state.visibleRows.first { it.size == 1 }.single().doc.name)
            state.setAnchor(state.visibleRows.first { it.size == 1 }.single())
            assertEquals(
                "mem://novels/002.txt",
                state.anchorUri.value
            )
        }
    }

    @Test
    fun excludedPath_coversNestedOutputAndDictBuilding() {
        assertTrue(SafBrowserState.isExcludedPath("novels/A/翻訳完了_LLM/001.txt"))
        assertTrue(SafBrowserState.isExcludedPath(".dict_building/batch_0001.json"))
        assertFalse(SafBrowserState.isExcludedPath("A/001.txt"))
        assertFalse(SafBrowserState.isExcludedPath(""))
    }

    @Test
    fun excludedDir_extraExactCoversCustomOutput() {
        assertTrue(SafBrowserState.isExcludedDir("EN_Output", setOf("EN_Output")))
        assertFalse(SafBrowserState.isExcludedDir("EN_Output"))
        assertTrue(SafBrowserState.isExcludedDir(".dict_building"))
        assertTrue(SafBrowserState.isExcludedPath("EN_Output/001.txt", setOf("EN_Output")))
    }

    private fun CoroutineScope.stateWithExtra(
        store: InMemoryFileStore,
        extra: Set<String>
    ): SafBrowserState {
        return SafBrowserState(store, this, "mem://novels", "novels", extra)
    }

    @Test
    fun collect_excludesLangCacheLegacyFile() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        val cache = store.createFile(root.uri, ".lang_cache.txt", "text/plain")!!
        store.writeText(cache.uri, "cache")
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        state.toggle(state.rows.value.first { it.doc.name == ".lang_cache.txt" })
        val r = state.collectTargets()
        assertTrue(r.targets.isEmpty())
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test
    fun collect_targetsInNaturalPathOrder() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        for (name in listOf("10.txt", "2.txt", "1.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        state.selectAllVisible(state.rows.value.filter { !it.doc.isDirectory })
        val r = state.collectTargets()
        assertEquals(
            listOf("1.txt", "2.txt", "10.txt"),
            r.targets.map { it.displayName }
        )
    }

    @Test
    fun collect_excludesCustomOutputDirAndFilesUnderIt() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        val out = store.createDir(root.uri, "EN_Output")!!
        val f = store.createFile(out.uri, "001.txt", "text/plain")!!
        store.writeText(f.uri, "訳文")
        val state = stateWithExtra(store, setOf("EN_Output"))
        state.refresh()
        state.awaitLoaded()
        // フォルダ直指定・配下ファイル直指定の双方を落とす。
        state.toggle(state.rows.value.first { it.doc.name == "EN_Output" })
        state.enter(state.rows.value.first { it.doc.name == "EN_Output" })
        state.awaitLoaded()
        state.toggle(state.rows.value.first { it.doc.name == "001.txt" })
        val r = state.collectTargets()
        assertTrue(r.targets.isEmpty())
        assertEquals(2, r.skipped.size)
    }

    @Test
    fun sort_naturalOrdersJapaneseChapters() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        for (name in listOf("第10話.txt", "第2話.txt", "第1話.txt", "おまけ.txt")) {
            val f = store.createFile(root.uri, name, "text/plain")!!
            store.writeText(f.uri, name)
        }
        val state = state(store)
        state.refresh()
        state.awaitLoaded()
        assertEquals(
            listOf("おまけ.txt", "第1話.txt", "第2話.txt", "第10話.txt"),
            state.rows.value.map { it.doc.name }
        )
    }
}
