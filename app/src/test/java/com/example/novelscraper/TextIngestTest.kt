package com.example.novelscraper

import com.example.novelscraper.translation.common.ingest.DeclaredEncoding
import com.example.novelscraper.translation.common.ingest.DetectionMethod
import com.example.novelscraper.translation.common.ingest.HypothesisId
import com.example.novelscraper.translation.common.ingest.IngestResult
import com.example.novelscraper.translation.common.ingest.QuarantineReason
import com.example.novelscraper.translation.common.ingest.TextIngest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 取込エンジンの golden テスト (正本)。
 * 行列: 言語 × {正常・ゴミ混入・破損・短文・純ASCII・空・BOM} × 期待 {採用ID／隔離}。
 */
class TextIngestTest {

    private fun successOf(result: IngestResult): IngestResult.Success {
        assertTrue("expected Success but was $result", result is IngestResult.Success)
        return result as IngestResult.Success
    }

    private fun quarantinedOf(result: IngestResult): IngestResult.Quarantined {
        assertTrue("expected Quarantined but was $result", result is IngestResult.Quarantined)
        return result as IngestResult.Quarantined
    }

    @Test
    fun emptyIsSuccess() {
        val r = successOf(TextIngest.ingest(ByteArray(0)))
        assertEquals("", r.text)
        assertEquals(DetectionMethod.EMPTY, r.provenance.method)
    }

    @Test
    fun pureAsciiIsUtf8() {
        val r = successOf(TextIngest.ingest("Hello, world!\n".repeat(100).toByteArray(StandardCharsets.UTF_8)))
        assertEquals(HypothesisId.UTF8, r.provenance.canonicalId)
    }

    @Test
    fun strictUtf8Paths() {
        val ko = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(200)
        var r = successOf(TextIngest.ingest(ko.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(HypothesisId.UTF8, r.provenance.canonicalId)
        assertEquals(DetectionMethod.STRICT_UTF8, r.provenance.method)
        assertEquals(ko, r.text)

        val zh = "这是关于中国古典小说的故事。\n".repeat(200)
        r = successOf(TextIngest.ingest(zh.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(HypothesisId.UTF8, r.provenance.canonicalId)
        assertEquals(zh, r.text)

        // BOM は剥離される
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "吾輩は猫である。\n".repeat(100).toByteArray(StandardCharsets.UTF_8)
        r = successOf(TextIngest.ingest(bom))
        assertEquals(DetectionMethod.BOM, r.provenance.method)
        assertTrue(r.text.startsWith("吾輩は猫である。"))
        assertFalse(r.text.startsWith("\uFEFF"))
    }

    @Test
    fun koreanCp949Adopted() {
        val sample = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(1500)
        val cp949 = Charset.forName("x-windows-949")
        val r = successOf(TextIngest.ingest(sample.toByteArray(cp949)))
        assertEquals(HypothesisId.CP949, r.provenance.canonicalId)
        assertEquals(sample, r.text)
    }

    @Test
    fun legacyEucKrCanonicalizesToCp949() {
        // EUC-KR は CP949 の部分集合のため CP949 採用で可逆に読める
        val sample = "동해물과 백두산이 마르고 닳도록\n".repeat(500)
        val bytes = sample.toByteArray(Charset.forName("EUC-KR"))
        val r = successOf(TextIngest.ingest(bytes))
        assertEquals(HypothesisId.CP949, r.provenance.canonicalId)
        assertEquals(sample, r.text)
    }

    @Test
    fun chineseAdoptions() {
        val zh = "这是关于中国古典小说的故事。从前有一座山。\n".repeat(1500)
        var r = successOf(TextIngest.ingest(zh.toByteArray(Charset.forName("GB18030"))))
        assertEquals(HypothesisId.GB18030, r.provenance.canonicalId)
        assertEquals(zh, r.text)

        r = successOf(TextIngest.ingest(zh.toByteArray(Charset.forName("GB2312"))))
        assertEquals(HypothesisId.GB18030, r.provenance.canonicalId)
        assertEquals(zh, r.text)

        val trad = "這是關於中國古典小說的故事。\n".repeat(1500)
        r = successOf(TextIngest.ingest(trad.toByteArray(Charset.forName("Big5"))))
        assertEquals(HypothesisId.BIG5, r.provenance.canonicalId)
        assertEquals(trad, r.text)
    }

    @Test
    fun japaneseAdoptions() {
        val ja = "吾輩は猫である。名前はまだ無い。\n".repeat(1500)
        val sjis = Charset.forName("Windows-31J")
        var r = successOf(TextIngest.ingest(ja.toByteArray(sjis)))
        assertEquals(HypothesisId.SJIS, r.provenance.canonicalId)
        assertEquals(ja, r.text)

        r = successOf(TextIngest.ingest(ja.toByteArray(Charset.forName("EUC-JP"))))
        assertEquals(HypothesisId.EUC_JP, r.provenance.canonicalId)
        assertEquals(ja, r.text)
    }

    @Test
    fun singleByteAdoptions() {
        val cases = listOf(
            "windows-1251" to "Привет, мир! Это пример текста на русском языке для проверки кодировки. ",
            "windows-1256" to "مرحبا بالعالم! هذا نص تجريبي باللغة العربية لاختبار الترميز. ",
            "windows-1253" to "Γεια σου κόσμε! Αυτό είναι ένα δοκιμαστικό κείμενο στα ελληνικά. ",
            "windows-1255" to "שלום עולם! זהו טקסט בדיקה בעברית לבדיקת הקידוד. ",
            "TIS-620" to "สวัสดีชาวโลก! นี่คือข้อความทดสอบภาษาไทยสำหรับการตรวจสอบรหัสอักขระ ",
            "windows-1252" to "Bonjour le monde! Ceci est un texte d'exemple en français avec des accents: été, crème. ",
            "windows-1254" to "Merhaba dünya! Türkçe test metni: güzel, şeker, ılık, İstanbul, çalışan. ",
            "windows-1258" to "Chào cô! Thư này gửi từ quê nhà mùa thu. Lá vàng rơi đầy sân. "
        )
        val expected = mapOf(
            "windows-1251" to HypothesisId.W1251,
            "windows-1256" to HypothesisId.W1256,
            "windows-1253" to HypothesisId.W1253,
            "windows-1255" to HypothesisId.W1255,
            "TIS-620" to HypothesisId.TIS620,
            "windows-1252" to HypothesisId.W1252,
            "windows-1254" to HypothesisId.W1254,
            "windows-1258" to HypothesisId.W1258
        )
        for ((csName, line) in cases) {
            val cs = Charset.forName(csName)
            var text = line
            if (csName == "windows-1258") {
                text = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFC)
            }
            val sb = StringBuilder()
            while (sb.toString().toByteArray(cs).size < 70000) sb.append(text)
            val bytes = encodeReplace(cs, sb.toString())
            val r = successOf(TextIngest.ingest(bytes))
            assertEquals("$csName -> ${expected[csName]} but was ${r.provenance.canonicalId}", expected[csName], r.provenance.canonicalId)
        }
    }

    private fun encodeReplace(cs: Charset, s: String): ByteArray {
        val encoder = cs.newEncoder()
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val bb = encoder.encode(java.nio.CharBuffer.wrap(s))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    @Test
    fun gbTrapTurkishIsNotChinese() {
        // 西欧文が高バイト+ASCIIの並びでGB誤読される罠。言語 gate で阻止する
        val sample = "Merhaba dünya! Türkçe test metni: güzel, şeker, ılık, İstanbul, çalışan. Kahraman ormanda uzun süre yürüdü. "
        val cs = Charset.forName("windows-1254")
        val sb = StringBuilder()
        while (sb.toString().toByteArray(cs).size < 70000) sb.append(sample)
        val r = successOf(TextIngest.ingest(sb.toString().toByteArray(cs)))
        assertEquals(HypothesisId.W1254, r.provenance.canonicalId)
    }

    @Test
    fun streamOverloadReadsBeyond64KB() {
        val cp949 = Charset.forName("x-windows-949")
        val sb = StringBuilder()
        val line = "동해물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세.\n"
        while (sb.toString().toByteArray(cp949).size < 70000) sb.append(line)
        val bytes = sb.toString().toByteArray(cp949)
        val r = successOf(TextIngest.ingest(ByteArrayInputStream(bytes)))
        assertEquals(HypothesisId.CP949, r.provenance.canonicalId)
        assertEquals(sb.toString(), r.text)
    }

    @Test
    fun iso2022Family() {
        val ja = "吾輩は猫である。名前はまだ無い。".repeat(50)
        var r = successOf(TextIngest.ingest(ja.toByteArray(Charset.forName("ISO-2022-JP"))))
        assertEquals(HypothesisId.I2022JP, r.provenance.canonicalId)
        assertEquals(ja, r.text)

        val ko = "동해물과 백두산이".repeat(100)
        r = successOf(TextIngest.ingest(ko.toByteArray(Charset.forName("ISO-2022-KR"))))
        assertEquals(HypothesisId.I2022KR, r.provenance.canonicalId)
        assertEquals(ko, r.text)

        val bytes = byteArrayOf(
            0x1B, 0x24, 0x29, 0x41, 0x0E,
            0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte(),
            0x0F
        )
        r = successOf(TextIngest.ingest(bytes))
        assertEquals(HypothesisId.I2022CN, r.provenance.canonicalId)
        assertEquals("中文", r.text)
    }

    private fun splice(base: ByteArray, pos: Int, poison: ByteArray): ByteArray {
        val out = ByteArray(base.size + poison.size)
        System.arraycopy(base, 0, out, 0, pos)
        System.arraycopy(poison, 0, out, pos, poison.size)
        System.arraycopy(base, pos, out, pos + poison.size, base.size - pos)
        return out
    }

    @Test
    fun poisonedKoreanRescued() {
        // 実ファイル事故の再現: CP949文中のゴミ2バイト (8E 3F)
        val cp949 = Charset.forName("x-windows-949")
        val clean = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(1500).toByteArray(cp949)
        val poisoned = splice(clean, 2000, byteArrayOf(0x8E.toByte(), 0x3F.toByte()))
        val r = successOf(TextIngest.ingest(poisoned))
        assertEquals(HypothesisId.CP949, r.provenance.canonicalId)
        assertEquals(1, r.text.count { it == '�' })
        assertTrue(r.text.startsWith("무간의 지배자는"))
    }

    @Test
    fun poisonedChineseRescued() {
        val clean = "这是关于中国古典小说的故事。从前有一座山。\n".repeat(1500)
            .toByteArray(Charset.forName("GB18030"))
        val poisoned = splice(clean, 2000, byteArrayOf(0x8E.toByte(), 0x3F.toByte()))
        val r = successOf(TextIngest.ingest(poisoned))
        assertEquals(HypothesisId.GB18030, r.provenance.canonicalId)
        assertTrue(r.text.startsWith("这是关于"))
    }

    @Test
    fun poisonedJapaneseRescued() {
        val clean = "吾輩は猫である。名前はまだ無い。\n".repeat(1500)
            .toByteArray(Charset.forName("Windows-31J"))
        val poisoned = splice(clean, 2000, byteArrayOf(0x8E.toByte(), 0x3F.toByte()))
        val r = successOf(TextIngest.ingest(poisoned))
        assertEquals(HypothesisId.SJIS, r.provenance.canonicalId)
        assertTrue(r.text.startsWith("吾輩は猫である。"))
    }

    @Test
    fun poisonedUtf8KeepsUtf8() {
        val line = "무간의 지배자는 어둠 속에서 검을 들었다.\n"
        val clean = line.repeat(1500).toByteArray(StandardCharsets.UTF_8)
        val pos = line.toByteArray(StandardCharsets.UTF_8).size * 40
        val poisoned = splice(clean, pos, byteArrayOf(0xFF.toByte()))
        val r = successOf(TextIngest.ingest(poisoned))
        assertEquals(HypothesisId.UTF8, r.provenance.canonicalId)
        assertEquals(1, r.text.count { it == '�' })
        assertTrue(r.text.startsWith("무간의 지배자는"))
    }

    @Test
    fun poisonedRussianDegradesInLanguage() {
        // 破損単バイト文は他言語に誤救済されず自言語で優雅に劣化する
        val sample = "Привет, мир! Это пример текста на русском языке для проверки кодировки. "
        val cs = Charset.forName("windows-1251")
        val clean = sample.repeat(900).toByteArray(cs)
        val poisoned = splice(clean, 2000, byteArrayOf(0x98.toByte()))
        val r = successOf(TextIngest.ingest(poisoned))
        assertEquals(HypothesisId.W1251, r.provenance.canonicalId)
        assertTrue(r.text.contains("Привет"))
        assertEquals(1, r.text.count { it == '�' })
    }

    @Test
    fun heavilyCorruptIsQuarantined() {
        // 全仮説で破損するバイト列は隔離 (成功を返さないことが契約)
        val pattern = byteArrayOf(
            0x00.toByte(), 0x98.toByte(), 0x81.toByte(),
            0x8D.toByte(), 0xFF.toByte(), 0x80.toByte()
        )
        val bytes = ByteArray(2000) { i -> pattern[i % pattern.size] }
        val q = quarantinedOf(TextIngest.ingest(bytes))
        assertEquals(QuarantineReason.UNDETECTABLE, q.reason)
    }

    @Test
    fun shortKoreanIsAdopted() {
        // 短文でも言語証拠があれば採用 (最小証拠量は短さに応じて縮小)
        val sample = "무간의 지배자는 어둠 속에서 검을 들었다."
        val r = successOf(
            TextIngest.ingest(sample.toByteArray(Charset.forName("x-windows-949")))
        )
        assertEquals(HypothesisId.CP949, r.provenance.canonicalId)
        assertEquals(sample, r.text)
    }

    @Test
    fun declaredEncodingWins() {
        val sample = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(500)
        val bytes = sample.toByteArray(Charset.forName("x-windows-949"))
        val r = successOf(TextIngest.ingest(bytes, DeclaredEncoding.CP949))
        assertEquals(HypothesisId.CP949, r.provenance.canonicalId)
        assertEquals(DetectionMethod.DECLARED, r.provenance.method)
        assertEquals(sample, r.text)
    }

    @Test
    fun declaredMismatchIsQuarantined() {
        val sample = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(500)
        val bytes = sample.toByteArray(Charset.forName("x-windows-949"))
        val q = quarantinedOf(TextIngest.ingest(bytes, DeclaredEncoding.GB18030))
        assertEquals(QuarantineReason.DECLARED_MISMATCH, q.reason)
    }

    @Test
    fun getOrThrowFailsClosed() {
        val pattern = byteArrayOf(
            0x00.toByte(), 0x98.toByte(), 0x81.toByte(),
            0x8D.toByte(), 0xFF.toByte(), 0x80.toByte()
        )
        val bytes = ByteArray(2000) { i -> pattern[i % pattern.size] }
        try {
            TextIngest.ingest(bytes).getOrThrow()
            fail("expected IOException for quarantined ingest")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("quarantined"))
        }
    }
}
