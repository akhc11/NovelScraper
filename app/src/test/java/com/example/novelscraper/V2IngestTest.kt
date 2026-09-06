package com.example.novelscraper

import com.example.novelscraper.translation.common.ingest.IngestResult as OldResult
import com.example.novelscraper.translation.common.ingest.TextIngest as OldIngest
import com.example.novelscraper.translation.v2.pipeline.V2CharsetId
import com.example.novelscraper.translation.v2.pipeline.V2ChunkVerifier
import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.pipeline.V2DetectMethod
import com.example.novelscraper.translation.v2.pipeline.V2Ingest
import com.example.novelscraper.translation.v2.pipeline.V2IngestResult
import com.example.novelscraper.translation.v2.pipeline.V2QuarantineReason
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class V2IngestTest {

    private val jaText = "これは冒険の物語です。主人公は平凡な少年でしたが、ある日突然不思議な力に覚醒しました。"
    private val koText = "주인공은 평범한 소년이었지만 어느 날 신비한 힘을 각성하게 되었다."
    private val zhText = "这是一个关于冒险的故事。主角从一个普通的少年成长为世界的救世主。"

    private fun sjis(text: String) = text.toByteArray(Charset.forName("Windows-31J"))
    private fun cp949(text: String) = text.toByteArray(Charset.forName("x-windows-949"))
    private fun gb18030(text: String) = text.toByteArray(Charset.forName("GB18030"))

    private fun successOf(result: V2IngestResult): String {
        assertTrue("expected Success, got $result", result is V2IngestResult.Success)
        return (result as V2IngestResult.Success).text
    }

    @Test
    fun testIngest_Utf8() {
        val result = V2Ingest.ingest(jaText.toByteArray(Charsets.UTF_8))
        assertEquals(jaText, successOf(result))
        val provenance = (result as V2IngestResult.Success).provenance
        assertEquals(V2CharsetId.UTF8, provenance.id)
        assertEquals(V2DetectMethod.STRICT_UTF8, provenance.method)
    }

    @Test
    fun testIngest_Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            jaText.toByteArray(Charsets.UTF_8)
        val result = V2Ingest.ingest(bytes)
        assertEquals(jaText, successOf(result))
        assertEquals(V2DetectMethod.BOM, (result as V2IngestResult.Success).provenance.method)
    }

    @Test
    fun testIngest_Sjis() {
        val result = V2Ingest.ingest(sjis(jaText))
        assertEquals(jaText, successOf(result))
        assertEquals(V2CharsetId.SJIS, (result as V2IngestResult.Success).provenance.id)
    }

    @Test
    fun testIngest_Cp949() {
        val result = V2Ingest.ingest(cp949(koText))
        assertEquals(koText, successOf(result))
        assertEquals(V2CharsetId.CP949, (result as V2IngestResult.Success).provenance.id)
    }

    @Test
    fun testIngest_Gb18030() {
        val result = V2Ingest.ingest(gb18030(zhText))
        assertEquals(zhText, successOf(result))
        assertEquals(V2CharsetId.GB18030, (result as V2IngestResult.Success).provenance.id)
    }

    @Test
    fun testIngest_Declared() {
        val ok = V2Ingest.ingest(sjis(jaText), V2DeclaredEncoding.SJIS)
        assertEquals(jaText, successOf(ok))
        // ASCII text declared as SJIS has no Japanese evidence -> quarantine.
        val mismatch = V2Ingest.ingest("plain ascii text".toByteArray(), V2DeclaredEncoding.SJIS)
        assertTrue(mismatch is V2IngestResult.Quarantined)
        assertEquals(V2QuarantineReason.DECLARED_MISMATCH, (mismatch as V2IngestResult.Quarantined).reason)
    }

    @Test
    fun testIngest_BinaryQuarantined() {
        // Corrupt ESC signature: sniffed as ISO-2022 but strict decode fails.
        val bytes = byteArrayOf(0x1B, 0x24, 0x42, 0xFF.toByte(), 0xFF.toByte()) +
            "ABC".toByteArray(Charsets.UTF_8)
        val result = V2Ingest.ingest(bytes)
        assertTrue("expected Quarantined, got $result", result is V2IngestResult.Quarantined)
        assertEquals(
            V2QuarantineReason.CORRUPT_ESCAPE,
            (result as V2IngestResult.Quarantined).reason
        )
    }

    @Test
    fun testIngest_Empty() {
        val result = V2Ingest.ingest(ByteArray(0))
        assertEquals("", successOf(result))
    }

    @Test
    fun testChunkVerifier_Layers() {
        assertNull(V2ChunkVerifier.verify("", V2CharsetId.SJIS))
        assertNull(V2ChunkVerifier.verify(jaText, V2CharsetId.UTF8))
        // FFFD flood.
        val flooded = "あ".repeat(50) + 0xFFFD.toChar().toString().repeat(10) + "あ".repeat(50)
        assertNotNull(V2ChunkVerifier.verify(flooded, V2CharsetId.SJIS))
        // Impossible script: hangul cannot come from an SJIS decode.
        val korean = "한".repeat(150)
        assertNotNull(V2ChunkVerifier.verify(korean, V2CharsetId.SJIS))
        assertNull(V2ChunkVerifier.verify(korean, V2CharsetId.CP949))
        // Missing expected script: latin-ext gibberish claimed as SJIS.
        val gibberish = "é".repeat(200)
        assertNotNull(V2ChunkVerifier.verify(gibberish, V2CharsetId.SJIS))
    }

    @Test
    fun testIngest_OldParity() {
        // New-old cross-check on identical byte strings (spec + tests only).
        val cases = listOf(
            jaText.toByteArray(Charsets.UTF_8),
            sjis(jaText),
            cp949(koText),
            gb18030(zhText),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                jaText.toByteArray(Charsets.UTF_8),
            ByteArray(20) { 0x80.toByte() },
            ByteArray(0)
        )
        for (bytes in cases) {
            val old = OldIngest.ingest(bytes, null)
            val new = V2Ingest.ingest(bytes, null)
            assertEquals(
                "class mismatch for ${bytes.take(8)}",
                old is OldResult.Success,
                new is V2IngestResult.Success
            )
            if (old is OldResult.Success && new is V2IngestResult.Success) {
                assertEquals(old.text, new.text)
            }
        }
    }
}
