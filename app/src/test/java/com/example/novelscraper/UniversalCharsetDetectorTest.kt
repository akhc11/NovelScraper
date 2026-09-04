package com.example.novelscraper

import com.example.novelscraper.translation.common.UniversalCharsetDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class UniversalCharsetDetectorTest {

    @Test
    fun testDetectUtf8() {
        val sample = "吾輩は猫である。名前はまだ無い。どこで生れたか頓と見当がつかぬ。"
        val bytes = sample.toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(bytes)
        val charset = UniversalCharsetDetector.detectCharsetFromStream(stream)
        assertEquals(StandardCharsets.UTF_8, charset)
    }

    @Test
    fun testDetectShiftJis() {
        val sample = "吾輩は猫である。名前はまだ無い。どこで生れたか頓と見当がつかぬ。"
        val sjis = Charset.forName("Shift_JIS")
        val bytes = sample.toByteArray(sjis)
        val stream = ByteArrayInputStream(bytes)
        val charset = UniversalCharsetDetector.detectCharsetFromStream(stream)
        assertEquals(Charset.forName("Windows-31J"), charset)
    }

    @Test
    fun testDetectKoreanCp949() {
        val sample = "동해 물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세."
        val cp949 = Charset.forName("x-windows-949")
        val bytes = sample.toByteArray(cp949)
        val stream = ByteArrayInputStream(bytes)
        val charset = UniversalCharsetDetector.detectCharsetFromStream(stream)
        assertEquals(cp949, charset)
    }

    @Test
    fun testDetectChineseGb18030() {
        val sample = "这是关于中国古典小说的故事。从前有一座山，山里有一座庙。"
        val gb = Charset.forName("GB18030")
        val bytes = sample.toByteArray(gb)
        val stream = ByteArrayInputStream(bytes)
        val charset = UniversalCharsetDetector.detectCharsetFromStream(stream)
        assertEquals(gb, charset)
    }
}
