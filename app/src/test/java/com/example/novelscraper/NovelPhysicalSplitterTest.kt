package com.example.novelscraper

import com.example.novelscraper.translation.common.NovelPhysicalSplitter
import com.example.novelscraper.translation.common.ingest.ChunkVerifier
import com.example.novelscraper.translation.common.ingest.HypothesisId
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

/**
 * 分割機構＋塊検証器のテスト (Ingest v2 世代の正本)。
 * 分割挙動の契約 (1000字前後・有害文字除去・超長行・空・単行) を固定し、
 * 検証器の3層 (FFFD・確定・軟) を golden で縛る。
 */
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
    fun testVerifier_FffdFlood() {
        val mojibake = "동해 물과 백두산이 ".repeat(50) + "�".repeat(500)
        assertNotNull(ChunkVerifier.verify(mojibake, HypothesisId.CP949))
        assertNull(ChunkVerifier.verify("短いテキスト�。", HypothesisId.UTF8))
        assertNull(ChunkVerifier.verify("", HypothesisId.CP949))
    }

    @Test
    fun testVerifier_DeterministicImpossibleScript() {
        // charsetが原理的に出せない文字種＝確定文字化け (長さ不問)
        val cp949 = HypothesisId.CP949
        assertNotNull(ChunkVerifier.verify("漢字とカタカナと한글混じり".repeat(10), HypothesisId.W1252))
        assertNotNull(ChunkVerifier.verify("あいうえお中文测试".repeat(10), HypothesisId.GB18030))
        assertNotNull(ChunkVerifier.verify("한글混じりテスト".repeat(10), HypothesisId.SJIS))
        assertNotNull(ChunkVerifier.verify("ひらがな混じり테스트".repeat(10), cp949))
        // 正規文は通る
        assertNull(ChunkVerifier.verify("Bonjour le monde! Café crème naïve. ".repeat(40), HypothesisId.W1252))
        assertNull(
            ChunkVerifier.verify(
                "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(100), cp949
            )
        )
        assertNull(
            ChunkVerifier.verify(
                "吾輩は猫である。名前はまだ無い。\n".repeat(100), HypothesisId.SJIS
            )
        )
        assertNull(
            ChunkVerifier.verify(
                "这是关于中国古典小说的故事。\n".repeat(100), HypothesisId.GB18030
            )
        )
    }

    @Test
    fun testVerifier_SoftMissingExpectedScript() {
        // 期待文字種ゼロ＋ASCII非主体＝化け
        val latinDense = "àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ ".repeat(8)
        assertNotNull(ChunkVerifier.verify(latinDense, HypothesisId.CP949))
        // ASCII主体 (英語引用) と漢字混じり (漢文引用) は正当のため通す
        val englishPassage = "Hello world. This is a quoted song lyric. ".repeat(30) +
            "한국어 문장입니다. ".repeat(5)
        assertNull(ChunkVerifier.verify(englishPassage, HypothesisId.CP949))
        val hanjaPassage = "大韓民國 萬歲 檀君神話 ".repeat(20)
        assertNull(ChunkVerifier.verify(hanjaPassage, HypothesisId.CP949))
    }

    @Test
    fun testVerifier_LatinGibberishDensity() {
        val cp949 = Charset.forName("x-windows-949")
        val w1252 = Charset.forName("windows-1252")
        // 実デコード経路の再現: CP949韓国語を1252で読んだ7000字チャンク
        val bogusBytes = "무간의 지배자는 어둠 속에서 검을 들었다.\n".repeat(1500).toByteArray(cp949)
        val bogus = String(bogusBytes, w1252).take(7000)
        val reason = ChunkVerifier.verify(bogus, HypothesisId.W1252)
        assertNotNull(reason)
        assertTrue(reason!!.contains("latin-gibberish"))
        // 正規フランス語 (1252) は通る
        val french = "Bonjour le monde! Ceci est un texte d'exemple en français avec des accents: été, crème, naïve, cœur. Le héros marcha longtemps. ".repeat(40)
        assertNull(ChunkVerifier.verify(french, HypothesisId.W1252))
        // 結合文字あり (分解ベトナム語) は除外
        val denseBase = "àáâãäå".repeat(30)
        assertNotNull(ChunkVerifier.verify(denseBase, HypothesisId.W1258))
        assertNull(ChunkVerifier.verify(denseBase + "éẽ".repeat(5), HypothesisId.W1258))
    }

    @Test
    fun testVerifier_UnicodeSkipsScriptChecks() {
        // UTF-8 は何語でもあり得るため FFFD のみ見る
        val clean = "동해 물과 백두산이 마르고 닳도록 하느님이 보우하사 우리나라 만세.\n".repeat(100) +
            "これは冒険の物語です。主人公は荒野を歩き続けました。\n".repeat(100)
        assertNull(ChunkVerifier.verify(clean, HypothesisId.UTF8))
    }
}
