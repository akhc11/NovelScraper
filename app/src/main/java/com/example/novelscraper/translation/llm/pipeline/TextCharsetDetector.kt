package com.example.novelscraper.translation.llm.pipeline

import com.example.novelscraper.translation.common.ingest.TextIngest
import java.io.InputStream

/**
 * LLM パイプライン用テキスト取込アダプタ。
 * 実装の本体は取込エンジン TextIngest に集約される。
 * 隔離・失敗時は例外 (呼び元は null・失敗扱いで処理する)。
 */
object TextCharsetDetector {

    fun readTextAutoDetect(inputStream: InputStream): String =
        TextIngest.ingest(inputStream).getOrThrow()

    fun decodeBytes(bytes: ByteArray): String =
        TextIngest.ingest(bytes).getOrThrow()
}
