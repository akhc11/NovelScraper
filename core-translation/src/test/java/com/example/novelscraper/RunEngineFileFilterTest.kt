package com.example.novelscraper

import com.example.novelscraper.translation.v2.engine.RunEngine
import com.example.novelscraper.translation.v2.infra.VDoc
import org.junit.Assert.*
import org.junit.Test

class RunEngineFileFilterTest {

    private fun doc(uri: String, name: String) = VDoc(uri = uri, name = name, isDirectory = false)

    @Test
    fun emptyAllowMeansAll() {
        val listed = listOf(doc("u1", "a1.txt"), doc("u2", "a2.txt"))
        assertEquals(listed, RunEngine.applyFileFilter(listed, emptySet()))
    }

    @Test
    fun nonEmptyAllowKeepsOnlySelected() {
        val listed = listOf(doc("u1", "a1.txt"), doc("u2", "a2.txt"), doc("u3", "a3.txt"))
        val filtered = RunEngine.applyFileFilter(listed, setOf("u1"))
        assertEquals(listOf("a1.txt"), filtered.map { it.name })
    }

    @Test
    fun unknownAllowYieldsEmpty() {
        val listed = listOf(doc("u1", "a1.txt"))
        assertTrue(RunEngine.applyFileFilter(listed, setOf("unknown")).isEmpty())
    }
}
