package com.example.novelscraper.translation.common

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * あらゆる海外・国内小説の生テキストに対応する万能文字コード自動判別エンジン。
 * 
 * 対応エンコーディング:
 * - Unicode: UTF-8 (BOMあり/なし), UTF-16LE, UTF-16BE
 * - 韓国語: x-windows-949 (CP949 / MS949, ハングル11,172文字完全網羅), EUC-KR
 * - 中国語: GB18030, GBK, Big5 (繁体字)
 * - 日本語: Shift_JIS (Windows-31J / CP932), EUC-JP
 * 
 * 判定方式:
 * 1. BOM解析による決定論的判定
 * 2. NIO による厳格な UTF-8 検証 (REPORT)
 * 3. 各レガシーエンコーディング候補によるデコード試行 + 自然言語スコアリング (かな・ハングル・CJK漢字・制御文字ペナルティ)
 */
object UniversalCharsetDetector {

    // 韓国語 (俗語・NSFWハングル全11,172文字を網羅する x-windows-949 を最優先)
    private val CP949: Charset by lazy {
        try { Charset.forName("x-windows-949") } catch (_: Exception) {
            try { Charset.forName("MS949") } catch (_: Exception) {
                try { Charset.forName("EUC-KR") } catch (_: Exception) { StandardCharsets.UTF_8 }
            }
        }
    }
    private val EUC_KR: Charset by lazy {
        try { Charset.forName("EUC-KR") } catch (_: Exception) { CP949 }
    }

    // 中国語 (簡体字国家標準 GB18030 / GBK および 繁体字 Big5)
    private val GB18030: Charset by lazy {
        try { Charset.forName("GB18030") } catch (_: Exception) {
            try { Charset.forName("GBK") } catch (_: Exception) { StandardCharsets.UTF_8 }
        }
    }
    private val BIG5: Charset by lazy {
        try { Charset.forName("Big5") } catch (_: Exception) { StandardCharsets.UTF_8 }
    }

    // 日本語 (Shift_JIS / Windows-31J および EUC-JP)
    private val SHIFT_JIS: Charset by lazy {
        try { Charset.forName("Windows-31J") } catch (_: Exception) {
            try { Charset.forName("Shift_JIS") } catch (_: Exception) { StandardCharsets.UTF_8 }
        }
    }
    private val EUC_JP: Charset by lazy {
        try { Charset.forName("EUC-JP") } catch (_: Exception) { StandardCharsets.UTF_8 }
    }

    private val LEGACY_CANDIDATES: List<Charset> by lazy {
        listOf(SHIFT_JIS, CP949, GB18030, EUC_KR, BIG5, EUC_JP)
            .distinct()
            .filter { it != StandardCharsets.UTF_8 }
    }

    /**
     * InputStream からバイト列を読み取り、万能文字コード自動判別でデコードする。
     */
    fun readTextAutoDetect(inputStream: InputStream): String {
        val bytes = inputStream.readBytes()
        return decodeBytes(bytes)
    }

    /**
     * InputStream から先頭 sampleSizeBytes (デフォルト64KB) を読み取って文字コードを自動判別する。
     */
    fun detectCharsetFromStream(inputStream: InputStream, sampleSizeBytes: Int = 65536): Charset {
        val buf = ByteArray(sampleSizeBytes)
        var totalRead = 0
        while (totalRead < sampleSizeBytes) {
            val read = inputStream.read(buf, totalRead, sampleSizeBytes - totalRead)
            if (read == -1) break
            totalRead += read
        }
        val sample = if (totalRead == sampleSizeBytes) buf else buf.copyOf(totalRead)
        return detectCharset(sample)
    }

    /**
     * バイト配列から最適な文字コード（Charset）を自動判別する。
     */
    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.isEmpty()) return StandardCharsets.UTF_8

        // 1. BOM チェック (UTF-8, UTF-16LE, UTF-16BE)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }

        // 2. 厳格な UTF-8 デコードを試行 (不正バイトがあれば即座に例外をスロー)
        try {
            val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            utf8Decoder.decode(ByteBuffer.wrap(bytes))
            return StandardCharsets.UTF_8
        } catch (_: Exception) {}

        // 3. レガシーエンコーディング候補のスコアリング評価
        var bestCharset: Charset = StandardCharsets.UTF_8
        var bestScore = Int.MIN_VALUE

        for (charset in LEGACY_CANDIDATES) {
            try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                val score = calculateNaturalnessScore(text, charset)
                if (score > bestScore) {
                    bestScore = score
                    bestCharset = charset
                }
            } catch (_: Exception) {}
        }

        return if (bestScore > 0) bestCharset else StandardCharsets.UTF_8
    }

    /**
     * バイト配列から最適な文字コードを自動判別して文字列に変換する。
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. BOM チェック (UTF-8, UTF-16LE, UTF-16BE)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        // 2. 厳格な UTF-8 デコードを試行 (不正バイトがあれば即座に例外をスロー)
        try {
            val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = utf8Decoder.decode(ByteBuffer.wrap(bytes))
            return charBuffer.toString()
        } catch (_: CharacterCodingException) {
            // UTF-8 ではない -> レガシーエンコーディング候補のスコアリングへ移行
        } catch (_: Exception) {}

        // 3. レガシーエンコーディング候補のスコアリング評価
        var bestText: String? = null
        var bestScore = Int.MIN_VALUE

        for (charset in LEGACY_CANDIDATES) {
            try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                val score = calculateNaturalnessScore(text, charset)
                if (score > bestScore) {
                    bestScore = score
                    bestText = text
                }
            } catch (_: Exception) {
                // デコードエラー (不正バイトあり)
            }
        }

        if (bestText != null && bestScore > 0) {
            return bestText
        }

        // 4. 最終フォールバック (置換文字を許容してUTF-8またはCP949でデコード)
        return try {
            CP949.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * デコードされた文字列の自然さ (言語固有の文字比率と制御文字ペナルティ) をスコアリング。
     * エンコーディングと文字種の排他性（例: CP949でひらがな、Shift_JISでハングル、GB18030でハングル等は不正化け）を厳格判定。
     */
    private fun calculateNaturalnessScore(text: String, charset: Charset): Int {
        if (text.isEmpty()) return 0
        var score = 0
        var controlCharCount = 0
        var hiraganaKatakanaCount = 0
        var hangulCount = 0
        var cjkCount = 0
        var asciiCount = 0
        val sampleLen = text.length.coerceAtMost(50000)

        for (i in 0 until sampleLen) {
            val ch = text[i]
            when {
                // ひらがな・カタカナ -> 日本語固有
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> {
                    hiraganaKatakanaCount++
                }
                // ハングル (完成型・拡張完成型・字母) -> 韓国語固有
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F' -> {
                    hangulCount++
                }
                // CJK統合漢字 -> 中国語・日本語・韓国語
                ch in '\u4E00'..'\u9FFF' -> {
                    cjkCount++
                }
                // 一般的な英数字・記号・改行・スペース
                ch in ' '..'~' || ch == '\n' || ch == '\r' || ch == '\t' -> {
                    asciiCount++
                }
                // 制御文字や未定義・私用領域 -> 強力なペナルティ
                ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' -> {
                    controlCharCount++
                }
                ch in '\uE000'..'\uF8FF' || ch == '\uFFFD' -> {
                    controlCharCount++
                }
            }
        }

        // 制御文字が多すぎる場合は明らかな文字化け
        if (controlCharCount > sampleLen * 0.02) {
            return -100000
        }

        val csName = charset.name().uppercase()
        val isJapanese = csName.contains("SJIS") || csName.contains("SHIFT_JIS") || csName.contains("932") || csName.contains("EUC-JP")
        val isKorean = csName.contains("949") || csName.contains("EUC-KR")
        val isChinese = csName.contains("GB") || csName.contains("BIG5")

        // 言語固有文字の不整合判定（別言語文字コードでデコードしたことによる化けを完全排除）
        if (isKorean && hiraganaKatakanaCount > 0) {
            return -100000
        }
        if (isChinese && (hangulCount > 0 || hiraganaKatakanaCount > 0)) {
            return -100000
        }
        if (isJapanese && hangulCount > 0) {
            return -100000
        }

        // 正当な文字へのスコア加算
        score += hiraganaKatakanaCount * 100
        score += hangulCount * 25
        score += cjkCount * 20
        score += asciiCount * 1
        score -= controlCharCount * 100

        return score
    }
}