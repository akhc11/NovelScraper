package com.example.novelscraper.translation.llm.pipeline

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextCharsetDetector {

    private val GB18030: Charset by lazy {
        try { Charset.forName("GB18030") } catch (e: Exception) { Charset.forName("GBK") }
    }
    private val EUC_KR: Charset by lazy {
        try { Charset.forName("EUC-KR") } catch (e: Exception) { StandardCharsets.UTF_8 }
    }
    private val SHIFT_JIS: Charset by lazy {
        try { Charset.forName("Shift_JIS") } catch (e: Exception) { StandardCharsets.UTF_8 }
    }

    /**
     * InputStream からバイト列を読み取り、BOM および文字コードを自動判別してデコードする。
     */
    fun readTextAutoDetect(inputStream: InputStream): String {
        val bytes = inputStream.readBytes()
        return decodeBytes(bytes)
    }

    /**
     * バイト配列から最適な文字コードを推測して文字列に変換する。
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. BOM チェック
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        // 2. 厳格な UTF-8 デコードを試行
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = decoder.decode(ByteBuffer.wrap(bytes))
            return charBuffer.toString()
        } catch (e: Exception) {
            // UTF-8 デコード失敗 → レガシーエンコーディングを試行
        }

        // 3. レガシーエンコーディング候補のスコアリング評価
        val candidates = listOf(SHIFT_JIS, GB18030, EUC_KR)
        var bestText: String? = null
        var bestScore = -1

        for (charset in candidates) {
            try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                val score = calculateTextNaturalnessScore(text)
                if (score > bestScore) {
                    bestScore = score
                    bestText = text
                }
            } catch (e: Exception) {
                // デコードエラー
            }
        }

        if (bestText != null) {
            return bestText
        }

        // 4. フォールバック
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * デコードされた文字列の自然さ (日本語かな、ハングル、CJK漢字、一般的なASCIIの存在比率) をスコアリング
     */
    private fun calculateTextNaturalnessScore(text: String): Int {
        var score = 0
        for (ch in text) {
            when {
                // ひらがな・カタカナ (Shift_JIS特有の強いシグナル)
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> score += 10
                // ハングル (EUC-KR特有の強いシグナル)
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' -> score += 10
                // CJK統合漢字
                ch in '\u4E00'..'\u9FFF' -> score += 3
                // 一般的な英数・記号・改行・スペース
                ch in ' '..'~' || ch == '\n' || ch == '\r' || ch == '\t' -> score += 1
                // 制御文字や私用領域はペナルティ
                ch.isISOControl() || ch in '\uE000'..'\uF8FF' -> score -= 10
            }
        }
        return score
    }
}