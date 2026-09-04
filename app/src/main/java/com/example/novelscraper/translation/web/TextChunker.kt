package com.example.novelscraper.translation.web

object TextChunker {

    private const val DEFAULT_MAX_CHUNK_SIZE = 2000

    /**
     * テキストを行単位を優先して指定された最大文字数以下のチャンクに分割する。
     * 元テキストの文字の欠落・重複がないこと（chunks.joinToString("") == text）を保証する。
     */
    fun splitIntoChunks(text: String, maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE): List<String> {
        if (text.isEmpty()) return emptyList()
        if (maxChunkSize <= 0 || text.length <= maxChunkSize) return listOf(text)

        val chunks = mutableListOf<String>()
        val lines = splitPreservingDelimiters(text, '\n')
        val currentChunk = StringBuilder()

        for (line in lines) {
            if (line.length > maxChunkSize) {
                // 現在のバッファがあれば先にチャンク化
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk.clear()
                }
                // 1行自体が上限を超えているので分割
                val subChunks = splitLongLine(line, maxChunkSize)
                chunks.addAll(subChunks)
            } else if (currentChunk.length + line.length > maxChunkSize) {
                // 次の行を加えると上限を超えるため、現在までを確定
                chunks.add(currentChunk.toString())
                currentChunk.clear()
                currentChunk.append(line)
            } else {
                currentChunk.append(line)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks
    }

    /**
     * 改行文字を含めたまま行ごとに分割する（末尾の空行や改行も保持）。
     */
    private fun splitPreservingDelimiters(text: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(delimiter, start)
            if (index == -1) {
                result.add(text.substring(start))
                break
            } else {
                result.add(text.substring(start, index + 1))
                start = index + 1
            }
        }
        return result
    }

    /**
     * 上限を超える単一行を優先度順の区切り（空白・文末・読点・文字境界）で分割する。
     * サロゲートペア（絵文字・異体字）の真ん中での切断による文字化けを防止する。
     */
    private fun splitLongLine(line: String, maxChunkSize: Int): List<String> {
        val result = mutableListOf<String>()
        var remaining = line

        while (remaining.length > maxChunkSize) {
            val candidate = remaining.substring(0, maxChunkSize)
            val splitIndex = findBestSplitIndex(candidate)

            var actualSplitIndex = if (splitIndex > 0) splitIndex else maxChunkSize
            // サロゲートペアの真ん中での切断を防止（上位サロゲートの手前で切る）
            if (actualSplitIndex > 0 && actualSplitIndex < remaining.length && remaining[actualSplitIndex - 1].isHighSurrogate()) {
                actualSplitIndex--
            }
            if (actualSplitIndex <= 0) {
                actualSplitIndex = if (remaining.length >= 2 && remaining[0].isHighSurrogate()) 2 else 1
            }

            result.add(remaining.substring(0, actualSplitIndex))
            remaining = remaining.substring(actualSplitIndex)
        }

        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }

        return result
    }

    /**
     * 候補文字列の中で最も自然な分割点（末尾側の区切り文字の直後）を見つける。
     */
    private fun findBestSplitIndex(candidate: String): Int {
        // 優先度1: 空白・タブ・改行の直後
        val whitespaceIndex = candidate.indexOfLast { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '　' }
        if (whitespaceIndex > candidate.length / 2) {
            return whitespaceIndex + 1
        }

        // 優先度2: 句点・文末記号の直後 (。 ! ? ！？ .)
        val sentenceEndIndex = candidate.indexOfLast {
            it == '。' || it == '！' || it == '？' || it == '!' || it == '?' || it == '.'
        }
        if (sentenceEndIndex > candidate.length / 2) {
            return sentenceEndIndex + 1
        }

        // 優先度3: 読点・カンマの直後 (、 ,)
        val commaIndex = candidate.indexOfLast { it == '、' || it == ',' }
        if (commaIndex > candidate.length / 2) {
            return commaIndex + 1
        }

        // 優先度4: 見つからなければ最大サイズ
        return -1
    }
}
