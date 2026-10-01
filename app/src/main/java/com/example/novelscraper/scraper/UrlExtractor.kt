package com.example.novelscraper.scraper

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

    /**
     * 共有intent等の複数ソースから優先順位順にURLを抽出する。
     * 各候補を順に [extractUrl] で評価し、最初に見つかったURLを返す。
     * 抽出責務は [extractUrl] に集約し、呼び元での正規表現コピーを作らない。
     */
    fun extractUrlFromCandidates(vararg candidates: String?): String? {
        for (candidate in candidates) {
            val url = extractUrl(candidate)
            if (url != null) return url
        }
        return null
    }

    /**
     * アドレスバー入力・共有URLなどの生入力を、WebViewに渡す遷移先URLに解決する（pure・JVMテスト可）。
     * - 前後空白を除去し、空は空のまま返す（呼び元で無視する）。
     * - http(s) URLを含む/ドメイン形式なら正規化URLを返す（スキームなしは https を補う）。
     * - それ以外は通常の検索語としてGoogle検索URLにフォールバックする。
     * 技術的根拠1行：android.util.Patterns/URLUtilは単体テストのJVM上で未mockのため、純Kotlin正規表現に寄せて振る舞いを固定する。
     */
    fun resolveNavigationTarget(rawInput: String?): String {
        val input = rawInput?.trim().orEmpty()
        if (input.isEmpty()) return ""
        val asUrl = extractUrl(input)
        if (asUrl != null) {
            return if (asUrl.lowercase().startsWith("http")) asUrl else "https://$asUrl"
        }
        val trimmed = input.trimEnd(*TRAILING_TRIM_CHARS)
        if (trimmed.isNotEmpty() && GENERIC_DOMAIN_REGEX.matches(trimmed)) {
            return "https://$trimmed"
        }
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }
}
