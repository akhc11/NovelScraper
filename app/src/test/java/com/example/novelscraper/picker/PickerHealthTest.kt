package com.example.novelscraper.picker

import com.example.novelscraper.translation.picker.RootHealth
import com.example.novelscraper.translation.picker.assessRootHealth
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PickerHealthTest {

    @Test
    fun assess_ok() {
        assertEquals(RootHealth.OK, assessRootHealth(true, true))
    }

    @Test
    fun assess_noPermissionTakesPrecedence() {
        assertEquals(RootHealth.NO_PERMISSION, assessRootHealth(false, true))
        assertEquals(RootHealth.NO_PERMISSION, assessRootHealth(false, false))
    }

    @Test
    fun assess_unreadable() {
        assertEquals(RootHealth.UNREADABLE, assessRootHealth(true, false))
    }

    @Test
    fun probe_memoryRoot() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        assertTrue(store.probe(root.uri))
    }

    @Test
    fun probe_missingIsFalse() = runBlocking {
        val store = InMemoryFileStore()
        assertFalse(store.probe("mem://nope"))
    }

    @Test
    fun probe_fileIsFalse() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        val f = store.createFile(root.uri, "a.txt", "text/plain")!!
        assertFalse(store.probe(f.uri))
    }

    @Test
    fun probe_deletedDirIsFalse() = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot("novels")
        val dir = store.createDir(root.uri, "A")!!
        assertTrue(store.probe(dir.uri))
        store.deleteRecursively(dir.uri)
        assertFalse(store.probe(dir.uri))
    }
}
