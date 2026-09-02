package com.example.novelscraper.translation.llm.pipeline

object BatchTranslator {

    /**
     * 複数ファイルを [SEG:1]...[SEG:2]... 形式で1つの入力テキストに結合する。
     */
    fun buildBatchInput(
        files: List<Pair<String, String>>, // (filename, content)
        enableCompletionMarker: Boolean = true
    ): String {
        val sb = StringBuilder()
        for ((index, file) in files.withIndex()) {
            val segNum = index + 1
            val cleansedContent = TextCleanser.cleanse(file.second).trim()
            sb.append("[SEG:$segNum]\n")
            sb.append(cleansedContent)
            if (index < files.size - 1) {
                sb.append("\n\n")
            }
        }
        if (enableCompletionMarker) {
            sb.append("\n[SRC_END]")
        }
        return sb.toString()
    }

    /**
     * モデルの応答から [SEG:N] 毎の訳文を抽出する。
     * モデルによる表記ゆれ (例: [SEG: 1], 【SEG:1】, **[SEG:1]**, SEG: 1) に柔軟に対応する。
     */
    fun parseBatchResponse(
        response: String,
        expectedCount: Int
    ): Map<Int, String>? {
        val resultMap = mutableMapOf<Int, String>()
        // 柔軟なセグメントマーカー正規表現
        val regex = Regex("""(?:\*{0,2})\[?【?SEG:?\s*(\d+)\]?】?(?:\*{0,2})""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(response).toList()

        if (matches.isEmpty()) return null

        for (i in matches.indices) {
            val match = matches[i]
            val segNum = match.groupValues[1].toIntOrNull() ?: continue
            val startIndex = match.range.last + 1
            val endIndex = if (i < matches.size - 1) matches[i + 1].range.first else response.length

            if (startIndex <= endIndex && startIndex <= response.length) {
                val segContent = response.substring(startIndex, endIndex.coerceAtMost(response.length)).trim()
                resultMap[segNum] = segContent
            }
        }

        // 全てのセグメント (1..expectedCount) が揃っているか確認
        for (n in 1..expectedCount) {
            if (!resultMap.containsKey(n) || resultMap[n].isNullOrBlank()) {
                return null
            }
        }

        return resultMap
    }
}