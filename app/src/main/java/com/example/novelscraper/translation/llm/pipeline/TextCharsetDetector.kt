package com.example.novelscraper.translation.llm.pipeline

import com.example.novelscraper.translation.common.UniversalCharsetDetector
import java.io.InputStream

/**
 * 既存の LLM パイプライン用 Charset 検出アダプタ。
 * 実装の本体は 万能判別エンジン UniversalCharsetDetector に集約され、
 * 韓国語(CP949/EUC-KR)、中国語(GB18030/Big5)、日本語(Shift_JIS/EUC-JP)を言語スコアリングで最高精度判別する。
 */
object TextCharsetDetector {

    fun readTextAutoDetect(inputStream: InputStream): String =
        UniversalCharsetDetector.readTextAutoDetect(inputStream)

    fun decodeBytes(bytes: ByteArray): String =
        UniversalCharsetDetector.decodeBytes(bytes)
}