package com.example.novelscraper.diagnostics

import com.example.novelscraper.translation.v2.service.TranslationDiagnostics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslationDiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun formatLine_containsTagAndMessage() {
        val line = TranslationDiagnostics.formatLine(0L, "memory", "onTrimMemory level=80")
        assertTrue(line.contains("[memory]"))
        assertTrue(line.contains("onTrimMemory level=80"))
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun runState_roundtrip() {
        val state = TranslationDiagnostics.RunState(true, 12345L, "folders=3")
        val parsed = TranslationDiagnostics.parseRunState(TranslationDiagnostics.serializeRunState(state))
        assertNotNull(parsed)
        assertEquals(true, parsed!!.running)
        assertEquals(12345L, parsed.timestampMs)
        assertEquals("folders=3", parsed.detail)
    }

    @Test
    fun parseRunState_rejectsBlankAndCorrupt() {
        assertNull(TranslationDiagnostics.parseRunState(null))
        assertNull(TranslationDiagnostics.parseRunState(""))
        assertNull(TranslationDiagnostics.parseRunState("hello\nworld\n"))
        // running行がなければnull（fail-closed）
        assertNull(TranslationDiagnostics.parseRunState("ts=1\ndetail=x\n"))
    }

    @Test
    fun prune_rotatesOnlyWhenOverCap() {
        val dir = tmp.newFolder()
        val log = File(dir, TranslationDiagnostics.LOG_NAME)
        log.writeText("small", Charsets.UTF_8)
        TranslationDiagnostics.prune(dir)
        assertTrue(log.exists())
        assertNull(if (File(dir, TranslationDiagnostics.LOG_PREV_NAME).exists()) true else null)

        // 上限超えでローテーションし、旧世代が残ること
        log.writeBytes(ByteArray((TranslationDiagnostics.MAX_BYTES + 10).toInt()))
        TranslationDiagnostics.prune(dir)
        assertTrue(File(dir, TranslationDiagnostics.LOG_PREV_NAME).exists())
    }

    @Test
    fun appendAndMarker_detectsInterruptedRun() {
        val dir = tmp.newFolder()
        TranslationDiagnostics.appendLineSync(dir, "lifecycle", "run started")
        assertTrue(File(dir, TranslationDiagnostics.LOG_NAME).readText().contains("run started"))

        File(dir, TranslationDiagnostics.STATE_NAME).writeText(
            TranslationDiagnostics.serializeRunState(
                TranslationDiagnostics.RunState(true, 1L, "folders=2")
            )
        )
        val parsed = TranslationDiagnostics.parseRunState(File(dir, TranslationDiagnostics.STATE_NAME).readText())
        assertEquals(true, parsed!!.running)

        File(dir, TranslationDiagnostics.STATE_NAME).writeText(
            TranslationDiagnostics.serializeRunState(
                TranslationDiagnostics.RunState(false, 2L, "")
            )
        )
        assertEquals(false, TranslationDiagnostics.parseRunState(File(dir, TranslationDiagnostics.STATE_NAME).readText())!!.running)
    }
}
