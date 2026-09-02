package com.example.novelscraper.translation.llm.pipeline

object NovelTextSplitter {
    const val DEFAULT_SPLIT_SIZE_BYTES = 8000

    /**
     * テキストを行単位・空行単位で limitBytes 以内に収まるように分割する
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
            } else {
                if (currentBytes > 0 && currentBytes + lineBytes > limitBytes) {
                    flush()
                }
                currentBuf.append(lineWithNewline)
                currentBytes += lineBytes
            }
        }

        flush()
        return results
    }
}