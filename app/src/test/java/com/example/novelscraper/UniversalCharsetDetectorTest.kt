package com.example.novelscraper

import com.example.novelscraper.translation.common.UniversalCharsetDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun testDetectLargeCp949Beyond64KB() {
        // 64KB境界でのマルチバイト分断によるUTF-8誤フォールバックの回帰テスト。
        // 先頭パディング0〜3で偶奇両アライメントを網羅する
        val cp949 = Charset.forName("x-windows-949")
        val line = "동해 물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세.\n"
        for (padding in 0..3) {
            val sb = StringBuilder("X".repeat(padding))
            while (sb.toString().toByteArray(cp949).size < 70000) sb.append(line)
            val bytes = sb.toString().toByteArray(cp949)
            val charset = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
            assertEquals("padding=$padding", cp949, charset)
        }
    }

    @Test
    fun testDetectLargeUtf8Beyond64KB() {
        // UTF-8韓国語の大ファイルは境界分断があってもUTF-8と判定されること
        val line = "프롤로그: 마법사의 탑에서 깨어난 소녀는 창밖을 바라보았다. 바람이 불고 있었다.\n"
        for (padding in 0..3) {
            val sb = StringBuilder("X".repeat(padding))
            while (sb.toString().toByteArray(StandardCharsets.UTF_8).size < 70000) sb.append(line)
            val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
            val charset = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
            assertEquals("padding=$padding", StandardCharsets.UTF_8, charset)
        }
    }

    @Test
    fun testDetectLargeSingleLineWithoutNewline() {        // 改行なし超長行(末尾バックオフ経路)の回帰テスト
        val cp949 = Charset.forName("x-windows-949")
        val cp949Line = "동해물과백두산이마르고닳도록".repeat(4000)
        val cp949Bytes = cp949Line.toByteArray(cp949)
        assertEquals(cp949, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(cp949Bytes)))

        val utf8Line = "프롤로그마법사의탑에서깨어난소녀는".repeat(3000)
        val utf8Bytes = utf8Line.toByteArray(StandardCharsets.UTF_8)
        assertEquals(StandardCharsets.UTF_8, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(utf8Bytes)))
    }

    @Test
    fun testDetectRussianWindows1251() {
        val sample = "Привет, мир! Это пример текста на русском языке для проверки кодировки. Главный герой долго шёл через лес."
        val cs = Charset.forName("windows-1251")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(sample.toByteArray(cs))))
    }

    @Test
    fun testDetectArabicWindows1256() {
        val sample = "مرحبا بالعالم! هذا نص تجريبي باللغة العربية لاختبار الترميز. سار البطل طويلا عبر الصحراء."
        val cs = Charset.forName("windows-1256")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(sample.toByteArray(cs))))
    }

    @Test
    fun testDetectGreekWindows1253() {
        val sample = "Γεια σου κόσμε! Αυτό είναι ένα δοκιμαστικό κείμενο στα ελληνικά για τον έλεγχο της κωδικοποίησης."
        val cs = Charset.forName("windows-1253")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(sample.toByteArray(cs))))
    }

    @Test
    fun testDetectHebrewWindows1255() {
        val sample = "שלום עולם! זהו טקסט בדיקה בעברית לבדיקת הקידוד. הגיבור הלך זמן רב ביער."
        val cs = Charset.forName("windows-1255")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(sample.toByteArray(cs))))
    }

    @Test
    fun testDetectThaiTis620() {
        val sample = "สวัสดีชาวโลก! นี่คือข้อความทดสอบภาษาไทยสำหรับการตรวจสอบรหัสอักขระ ตัวเอกเดินทางผ่านป่า"
        val cs = Charset.forName("TIS-620")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(sample.toByteArray(cs))))
    }

    @Test
    fun testDetectWesternWindows1252() {
        val cs = Charset.forName("windows-1252")
        val french = "Bonjour le monde! Ceci est un texte d'exemple en français avec des accents: été, crème, naïve, cœur. Le héros marcha longtemps."
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(french.toByteArray(cs))))
        val german = "Grüße aus München! Äpfel, Öl und süße Grüße zum Frühstück. Der Held wanderte lange durch den Wald."
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(german.toByteArray(cs))))
        val italian = "Ciao mondo! Questo è un testo di esempio in italiano: lunedì, però, città, perché. L'eroe camminò a lungo."
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(italian.toByteArray(cs))))
        val spanish = "Hola mundo! Este es un texto de ejemplo en español con tildes: camión, corazón, niño, pingüino. El héroe caminó mucho tiempo."
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(spanish.toByteArray(cs))))
    }

    @Test
    fun testDetectTurkishWindows1254() {
        val sample = "Merhaba dünya! Türkçe test metni: güzel, şeker, ılık, İstanbul, çalışan. Kahraman ormanda uzun süre yürüdü."
        val cs = Charset.forName("windows-1254")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(sample.toByteArray(cs))))
    }

    @Test
    fun testDetectVietnameseWindows1258() {
        // cp1258は事前合成セットに戻してから符号化 (実ファイルと同条件)
        val sample = "Chào cô! Thư này gửi từ quê nhà mùa thu. Lá vàng rơi đầy sân trước nhà. Mưa phùn bay nhẹ."
        val cs = Charset.forName("windows-1258")
        assertEquals(cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(cp1258Bytes(sample))))
    }

    @Test
    fun testDetectLargeSingleByteBeyond64KB() {
        // 64KB超の単バイト族ファイル (境界分断＋族内識別の回帰テスト)
        val cases = listOf(
            "windows-1251" to "Привет, мир! Это пример текста на русском языке для проверки кодировки. ",
            "windows-1256" to "مرحبا بالعالم! هذا نص تجريبي باللغة العربية لاختبار الترميز. ",
            "windows-1252" to "Bonjour le monde! Ceci est un texte d'exemple en français avec des accents: été, crème. ",
            "TIS-620" to "สวัสดีชาวโลก! นี่คือข้อความทดสอบภาษาไทยสำหรับการตรวจสอบรหัสอักขระ ",
        )
        for ((csName, line) in cases) {
            val cs = Charset.forName(csName)
            for (padding in 0..1) {
                val sb = StringBuilder("X".repeat(padding))
                while (sb.toString().toByteArray(cs).size < 70000) sb.append(line)
                val bytes = sb.toString().toByteArray(cs)
                assertEquals("$csName pad=$padding", cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes)))
            }
        }
    }

    @Test
    fun testGbFriendlyTurkishLargeIsNotChinese() {        // 西欧文が高バイト+ASCII文字の並びでGB18030として厳格に読めてしまう罠の回帰テスト。
        // CJKデコード時のASCII8割超ガードでGBを排除し、正しくwindows-1254と判定すること
        val line = "Merhaba dünya! Türkçe test metni: güzel, şeker, ılık, İstanbul. "
        val cs = Charset.forName("windows-1254")
        for (padding in 0..1) {
            val sb = StringBuilder("X".repeat(padding))
            while (sb.toString().toByteArray(cs).size < 70000) sb.append(line)
            val bytes = sb.toString().toByteArray(cs)
            assertEquals("pad=$padding", cs, UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun testDetectLegacyKoreanEucKr() {
        // 旧韓国語EUC-KR (CP949の部分集合のためx-windows-949判定で正しく読める)
        val sample = "동해 물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세. 옛날 옛적에 깊은 산속에."
        val bytes = sample.toByteArray(Charset.forName("EUC-KR"))
        val detected = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
        assertEquals(Charset.forName("x-windows-949"), detected)
        assertEquals(sample, UniversalCharsetDetector.decodeBytes(bytes))
    }

    @Test
    fun testDetectLegacyChineseGb2312() {
        // 旧中国語GB2312 (GB18030の下位互換)
        val sample = "这是关于中国古典小说的故事。从前有一座山，山里有一座庙。"
        val bytes = sample.toByteArray(Charset.forName("GB2312"))
        val detected = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
        assertEquals(Charset.forName("GB18030"), detected)
        assertEquals(sample, UniversalCharsetDetector.decodeBytes(bytes))
    }

    @Test
    fun testDetectLegacyEnglishSmartQuotes() {
        // 旧英語windows-1252 (スマートクォート入り。ギリシャ語誤判定の回帰テスト)
        val sample = "\u2018Hello,\u2019 said the programmer. \u201cIt\u2019s a na\u00efve approach \u2014 but it works\u2026\u201d He smiled."
        val bytes = sample.toByteArray(Charset.forName("windows-1252"))
        val detected = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
        assertEquals(Charset.forName("windows-1252"), detected)
        assertEquals(sample, UniversalCharsetDetector.decodeBytes(bytes))
    }

    @Test
    fun testDetectIso2022Jp() {
        val sample = "吾輩は猫である。名前はまだ無い。"
        val bytes = sample.toByteArray(Charset.forName("ISO-2022-JP"))
        val detected = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
        assertEquals(Charset.forName("ISO-2022-JP"), detected)
        assertEquals(sample, UniversalCharsetDetector.decodeBytes(bytes))
    }

    @Test
    fun testDetectIso2022Kr() {
        val sample = "동해물과 백두산이"
        val bytes = sample.toByteArray(Charset.forName("ISO-2022-KR"))
        val detected = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
        assertEquals(Charset.forName("ISO-2022-KR"), detected)
        assertEquals(sample, UniversalCharsetDetector.decodeBytes(bytes))
    }

    @Test
    fun testDetectIso2022Cn() {
        // JDKにISO-2022-CNエンコーダがないためバイト列を手組み (ESC $ ) A + SO + GB2312「中文」+ SI)
        val bytes = byteArrayOf(
            0x1B, 0x24, 0x29, 0x41, 0x0E,
            0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte(),
            0x0F
        )
        val detected = UniversalCharsetDetector.detectCharsetFromStream(ByteArrayInputStream(bytes))
        assertEquals(Charset.forName("ISO-2022-CN"), detected)
        assertEquals("中文", UniversalCharsetDetector.decodeBytes(bytes))
    }

    @Test
    fun testRescuePoisonedCp949SingleStrayByte() {
        // 実ファイル事故の再現: CP949文中のゴミ2バイト (8E 3F) でTier1は単バイト誤判定→Tier2B救済
        val cp949 = Charset.forName("x-windows-949")
        val line = "무간의 지배자는 어둠 속에서 검을 들었다.\n"
        val clean = line.repeat(1500).toByteArray(cp949)
        val pos = 2000
        val poisoned = ByteArray(clean.size + 2)
        System.arraycopy(clean, 0, poisoned, 0, pos)
        poisoned[pos] = 0x8E.toByte()
        poisoned[pos + 1] = 0x3F.toByte()
        System.arraycopy(clean, pos, poisoned, pos + 2, clean.size - pos)
        assertEquals(cp949, UniversalCharsetDetector.detectCharset(poisoned))
        // 全復号でもFFFDはゴミ由来の1個のみ (ガード素通り水準)
        val text = UniversalCharsetDetector.decodeBytes(poisoned)
        assertEquals(1, text.count { it == '�' })
        assertTrue(text.startsWith("무간의 지배자는"))
    }

    @Test
    fun testDecodePoisonedUtf8KeepsUtf8() {
        // UTF-8＋ゴミ混入はTier2A (全滅時 contest) / Tier2A-2 (単バイト勝者への挑戦) でUTF-8を守る
        val line = "무간의 지배자는 어둠 속에서 검을 들었다.\n"
        val clean = line.repeat(1500).toByteArray(StandardCharsets.UTF_8)
        // 文境界に挿入 (後続トレイル bytes を孤立させないため。FFFDはゴミ由来の1個に確定)
        val pos = line.toByteArray(StandardCharsets.UTF_8).size * 40
        val poisoned = ByteArray(clean.size + 1)
        System.arraycopy(clean, 0, poisoned, 0, pos)
        poisoned[pos] = 0xFF.toByte()
        System.arraycopy(clean, pos, poisoned, pos + 1, clean.size - pos)
        assertEquals(StandardCharsets.UTF_8, UniversalCharsetDetector.detectCharset(poisoned))
        val text = UniversalCharsetDetector.decodeBytes(poisoned)
        assertEquals(1, text.count { it == '�' })
        assertTrue(text.startsWith("무간의 지배자는"))
    }

    @Test
    fun testPoisonedRussianDegradesInLanguage() {
        // 破損単バイト文は韓国語等に誤救済されず自言語で優雅に劣化する (1251＋FFFD 1個)
        val sample = "Привет, мир! Это пример текста на русском языке для проверки кодировки. "
        val cs = Charset.forName("windows-1251")
        val clean = sample.repeat(900).toByteArray(cs)
        val pos = 2000
        val poisoned = ByteArray(clean.size + 1)
        System.arraycopy(clean, 0, poisoned, 0, pos)
        poisoned[pos] = 0x98.toByte()
        System.arraycopy(clean, pos, poisoned, pos + 1, clean.size - pos)
        assertEquals(cs, UniversalCharsetDetector.detectCharset(poisoned))
        val text = UniversalCharsetDetector.decodeBytes(poisoned)
        assertTrue(text.contains("Привет"))
        assertEquals(1, text.count { it == '�' })
    }

    private fun cp1258Bytes(text: String): ByteArray {
        var t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        listOf(
            "a\u0302" to "â", "A\u0302" to "Â", "e\u0302" to "ê", "E\u0302" to "Ê",
            "o\u0302" to "ô", "O\u0302" to "Ô", "a\u0306" to "ă", "A\u0306" to "Ă",
            "o\u031B" to "ơ", "O\u031B" to "Ơ", "u\u031B" to "ư", "U\u031B" to "Ư"
        ).forEach { (a, b) -> t = t.replace(a, b) }
        return t.toByteArray(Charset.forName("windows-1258"))
    }
}
