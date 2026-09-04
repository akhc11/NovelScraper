package com.example.novelscraper.translation.llm.pipeline

object NovelTextSplitter {
    const val DEFAULT_SPLIT_SIZE_BYTES = 8000

    /**
     * テキストを行単位・空行単位で limitBytes 以内に収まるように分割する。
     * 末尾の端数（たった1行だけ、または極小サイズ）が孤立チャンクにならないよう、直前パートへ自動マージする。
     */
    fun splitIntoChunks(
        text: String,
        limitBytes: Int = DEFAULT_SPLIT_SIZE_BYTES,
        prefix: String = "part",
        suffix: String = ".txt"
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val lines = text.lines()

        var partNumber = 1
        val currentBuf = StringBuilder()
        var currentBytes = 0

        fun flush() {
            if (currentBuf.isNotEmpty()) {
                val fileName = String.format("%s_%04d%s", prefix, partNumber, suffix)
                results.add(fileName to currentBuf.toString())
                partNumber++
                currentBuf.clear()
                currentBytes = 0
            }
        }

        for (line in lines) {
            val lineWithNewline = line + "\n"
            val lineBytes = lineWithNewline.toByteArray(Charsets.UTF_8).size

            if (line.isEmpty()) {
                currentBuf.append(lineWithNewline)
                currentBytes += lineBytes
                if (currentBytes >= limitBytes) {
                    flush()
                }
            } else if (lineBytes > limitBytes) {
                // 改行なし超長文段落: 句点(。)やピリオド(.)等で分割するフォールバック
                if (currentBuf.isNotEmpty()) flush()
                for (segment in splitLongLine(line, limitBytes)) {
                    val segWithNewline = segment + "\n"
                    currentBuf.append(segWithNewline)
                    currentBytes += segWithNewline.toByteArray(Charsets.UTF_8).size
                    flush()
                }
            } else {
                if (currentBytes > 0 && currentBytes + lineBytes > limitBytes) {
                    flush()
                }
                currentBuf.append(lineWithNewline)
                currentBytes += lineBytes
            }
        }

        flush()

        // 末尾の孤立端数チャンク（たった1行、または極小サイズ）の防止:
        // 2パート以上あり、最後のパートが極小（3行以下 または limitBytesの20%未満）の場合、
        // 独立した1行チャンクにせず直前のパートへ自動合流 (マージ) する
        if (results.size >= 2) {
            val lastPair = results.last()
            val lastText = lastPair.second.trim()
            val lastNonEmptyLines = lastText.lines().filter { it.isNotBlank() }
            val lastBytes = lastPair.second.toByteArray(Charsets.UTF_8).size
            val minTailThreshold = (limitBytes * 0.2).toInt().coerceIn(500, 5000)

            if (lastNonEmptyLines.size <= 2 || lastBytes < minTailThreshold) {
                val prevPair = results[results.size - 2]
                val mergedContent = prevPair.second.trimEnd() + "\n\n" + lastPair.second.trimStart()
                results[results.size - 2] = prevPair.first to mergedContent
                results.removeAt(results.size - 1)
            }
        }

        return results
    }

    /**
     * limitBytesを超える改行なし超長文を句点・ピリオド等の文境界で分割するフォールバック。
     * 句読点がない場合はそのまま1セグメントとして返す（これ以上の分割は不可能）。
     */
    private fun splitLongLine(line: String, limitBytes: Int): List<String> {
        // 句点・ピリオド・感嘆符・疑問符の直後で分割
        val sentences = Regex("""(?<=[。.！？!?])""").split(line).filter { it.isNotEmpty() }
        if (sentences.size <= 1) return listOf(line)

        val segments = mutableListOf<String>()
        val buf = StringBuilder()
        var bufBytes = 0

        for (sentence in sentences) {
            val sentenceBytes = sentence.toByteArray(Charsets.UTF_8).size
            if (bufBytes > 0 && bufBytes + sentenceBytes > limitBytes) {
                segments.add(buf.toString())
                buf.clear()
                bufBytes = 0
            }
            buf.append(sentence)
            bufBytes += sentenceBytes
        }
        if (buf.isNotEmpty()) {
            segments.add(buf.toString())
        }

        return segments.ifEmpty { listOf(line) }
    }
}
