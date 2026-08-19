package com.example.novelscraper

object UrlExtractor {
    private val HTTP_URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val GENERIC_DOMAIN_REGEX = Regex("""^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(/[^\s]*)?$""", RegexOption.IGNORE_CASE)
    private val TRAILING_TRIM_CHARS = charArrayOf(
        '。', '、', '！', '？', '…', '.', ',', ';', ':',
        ')', ']', '}', '>', '」', '』', '）', '】', '〉', '》', '］', '｝'
    )

    /**
     * テキスト内から最初に見つかった有効なURLを抽出する。
     * 見つからない場合は null を返す。
     */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        if (text.length > 5000) return null // 異常に長い入力に対する保護

        // 1. http:// または https:// で始まるURLを検索
        val match = HTTP_URL_REGEX.find(text)
        if (match != null) {
            val rawUrl = match.value.trim()
            return rawUrl.trimEnd(*TRAILING_TRIM_CHARS)
        }

        // 2. 単体でプロトコルなしのドメイン形式である場合のフォールバック
        val trimmed = text.trim().trimEnd(*TRAILING_TRIM_CHARS)
        if (GENERIC_DOMAIN_REGEX.matches(trimmed)) {
            return "https://$trimmed"
        }

        return null
    }
}
