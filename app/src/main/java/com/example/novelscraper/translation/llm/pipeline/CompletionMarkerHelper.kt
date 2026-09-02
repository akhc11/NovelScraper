package com.example.novelscraper.translation.llm.pipeline

object CompletionMarkerHelper {
    const val MARKER = "[SRC_END]"

    /**
     * 原文テキストの末尾に完了マーカーを追記する
     */
    fun appendMarker(sourceText: String, enabled: Boolean): String {
        return if (enabled) {
            sourceText.trimEnd() + "\n" + MARKER
        } else {
            sourceText
        }
    }

    /**
     * 出力テキストの末尾に完了マーカーが存在するか検証し、マーカーを完全に除去したテキストを返す。
     * マーカーが無い場合は null (途絶・品質NG) を返す。
     */
    fun checkAndStripMarker(content: String, enabled: Boolean): String? {
        if (!enabled) return content.trim()

        var text = content.trim()
        // マークダウンの閉じバッククォートがある場合は事前に除去して判定
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }

        if (text == MARKER) {
            return ""
        }

        if (text.endsWith(MARKER)) {
            val stripped = text.substring(0, text.length - MARKER.length).trim()
            return stripped
        }

        // マーカーが見つからない → 生成途絶
        return null
    }
}