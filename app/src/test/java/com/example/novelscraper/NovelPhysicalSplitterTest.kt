package com.example.novelscraper

import com.example.novelscraper.translation.common.NovelPhysicalSplitter
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class NovelPhysicalSplitterTest {

    @Test
    fun testSplitLinesIntoChunks_CharacterLimit() {
        // 1行約60文字のテキストを60行作成 (計約3,600文字)
        val lines = (1..60).map { i ->
            "これは第${i}行目のテスト文章です。主人公は荒野を歩き続け、やがて巨大な城の前に辿り着きました。城の門は固く閉ざされていました。"
        }

        // 1,000文字単位で分割
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(lines.asSequence(), splitSizeChars = 1000)

        assertTrue("Should split into multiple chunks", chunks.size >= 3)
        for (chunk in chunks) {
            // 末尾以外のチャンクは1,000文字前後であること
            assertTrue("Chunk should have content", chunk.isNotEmpty())
        }

        // 全チャンクの合計行数が60行であること
        val totalLines = chunks.sumOf { it.lines().filter { l -> l.isNotEmpty() }.size }
        assertEquals(60, totalLines)
    }

    @Test
    fun testSplitLinesIntoChunks_CleansesHarmfulCharacters() {
        // NULLバイト、埋め込みBOM、ゼロ幅スペースを含む行
        val dirtyLines = listOf(
            "第1話\uFEFFの\u0000タイトルです。",
            "本文に\u200Bゼロ幅\u0000スペースが\uFEFF混入しています。"
        )

        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(dirtyLines.asSequence(), splitSizeChars = 500)
        assertEquals(1, chunks.size)
        val result = chunks.first()

        assertFalse("Should not contain NULL byte", result.contains("\u0000"))
        assertFalse("Should not contain embedded BOM", result.contains("\uFEFF"))
        assertFalse("Should not contain zero-width space", result.contains("\u200B"))
        assertTrue("Should keep original readable text", result.contains("第1話のタイトルです。"))
        assertTrue("Should keep original readable text", result.contains("本文にゼロ幅スペースが混入しています。"))
    }

    @Test
    fun testSplitLinesIntoChunks_OversizedLineFallback() {
        // 改行が一切ない2,000文字の超長行
        val longText = "あ".repeat(2000)
        val lines = listOf(longText)

        // 500文字で分割
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(lines.asSequence(), splitSizeChars = 500)

        assertEquals("Should split into 4 chunks of 500 characters", 4, chunks.size)
        val totalA = chunks.sumOf { it.trim().length }
        assertEquals(2000, totalA)
    }

    @Test
    fun testSplitLinesIntoChunks_EmptySequence() {
        val emptyLines = emptyList<String>()
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(emptyLines.asSequence(), splitSizeChars = 500)
        assertTrue("Empty sequence should produce no chunks", chunks.isEmpty())
    }

    @Test
    fun testSplitLinesIntoChunks_SingleShortLine() {
        val lines = listOf("短い1行だけのテキスト。")
        val chunks = NovelPhysicalSplitter.splitLinesIntoChunks(lines.asSequence(), splitSizeChars = 500)
        assertEquals(1, chunks.size)
        assertEquals("短い1行だけのテキスト。\n", chunks.first())
    }

    @Test
    fun testIsMojibakeChunk_CleanText() {
        val clean = "동해 물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세.\n".repeat(100) +
            "これは冒険の物語です。主人公は荒野を歩き続けました。\n".repeat(100)
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk(clean))
    }

    @Test
    fun testIsMojibakeChunk_Mojibake() {
        // 誤Charset復号の再現: 大量のU+FFFD混じりテキスト
        val mojibake = "동해 물과 백두산이 ".repeat(50) + "\uFFFD".repeat(500)
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk(mojibake))
    }

    @Test
    fun testIsMojibakeChunk_IsolatedFffdIsNotMojibake() {
        // 原文由来の孤立した置換文字はスキップ対象にしない
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk("短いテキスト�。"))
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk(""))
    }

    @Test
    fun testMojibakeReason_DeterministicImpossibleScript() {
        // charsetが原理的に出せない文字種＝確定文字化け (長さ不問)
        val cp949 = Charset.forName("x-windows-949")
        val sjis = Charset.forName("Windows-31J")
        val gb = Charset.forName("GB18030")
        val w1252 = Charset.forName("windows-1252")
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk("漢字とカタカナと한글混じり".repeat(10), w1252))
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk("あいうえお中文测试".repeat(10), gb))
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk("한글混じりテスト".repeat(10), sjis))
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk("ひらがな混じり테스트".repeat(10), cp949))
        // 正規文は通る
        assertFalse(
            NovelPhysicalSplitter.isMojibakeChunk(
                "Bonjour le monde! Café crème naïve. ".repeat(40), w1252
            )
        )
        assertFalse(
            NovelPhysicalSplitter.isMojibakeChunk(
                "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(100), cp949
            )
        )
        assertFalse(
            NovelPhysicalSplitter.isMojibakeChunk(
                "吾輩は猫である。名前はまだ無い。\n".repeat(100), sjis
            )
        )
        assertFalse(
            NovelPhysicalSplitter.isMojibakeChunk(
                "这是关于中国古典小说的故事。\n".repeat(100), gb
            )
        )
    }

    @Test
    fun testMojibakeReason_SoftMissingExpectedScript() {
        val cp949 = Charset.forName("x-windows-949")
        // 期待文字種ゼロ＋ASCII非主体＝化け (ラテン拡張密文をCP949と誤認した場合)
        val latinDense = "àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ ".repeat(8)
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk(latinDense, cp949))
        // ASCII主体 (英語引用) と漢字混じり (漢文引用) は正当のため通す
        val englishPassage = "Hello world. This is a quoted song lyric. ".repeat(30) +
            "한국어 문장입니다. ".repeat(5)
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk(englishPassage, cp949))
        val hanjaPassage = "大韓民國 萬歲 檀君神話 ".repeat(20)
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk(hanjaPassage, cp949))
    }

    @Test
    fun testMojibakeReason_LatinGibberishDensity() {
        val w1252 = Charset.forName("windows-1252")
        val cp949 = Charset.forName("x-windows-949")
        // 実デコード経路の再現: CP949韓国語を1252で読んだ7000字チャンク
        val bogusBytes = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(1500).toByteArray(cp949)
        val bogus = String(bogusBytes, w1252).take(7000)
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk(bogus, w1252))
        assertTrue(NovelPhysicalSplitter.mojibakeReason(bogus, w1252)!!.contains("latin-gibberish"))
        // 正規フランス語 (1252) は通る
        val french = "Bonjour le monde! Ceci est un texte d'exemple en français avec des accents: été, crème, naïve, cœur. Le héros marcha longtemps. ".repeat(40)
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk(french, w1252))
        // 結合文字あり (分解ベトナム語) は除外
        val w1258 = Charset.forName("windows-1258")
        val denseBase = "àáâãäå".repeat(30)
        assertTrue(NovelPhysicalSplitter.isMojibakeChunk(denseBase, w1258))
        assertFalse(NovelPhysicalSplitter.isMojibakeChunk(denseBase + "êẽ".repeat(5), w1258))
    }
}
