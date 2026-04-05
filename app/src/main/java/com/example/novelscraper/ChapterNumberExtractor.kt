package com.example.novelscraper

/**
 * チャプター番号の抽出・整形ロジックを一元管理する。
 * MainActivity.performTestRun と ScrapingTask.processPage で共通利用。
 */
object ChapterNumberExtractor {

    private const val PAD_LENGTH = 4
    private val NUMBER_REGEX = Regex("(\\d+)")

    /**
     * スクレイピング結果とコンフィグからチャプター番号文字列を決定する。
     *
     * @param rawChapter JS から返されたチャプター文字列
     * @param configChapter ユーザ設定の chapter フィールド（@付きで手動指定）
     * @param currentUrl 現在のページURL（URLからの番号推測用）
     * @param manualCounter @指定時の手動カウンター値（null = 自動取得モード）
     * @return 整形済みチャプター番号文字列（例: "0001"）
     */
    fun extract(
        rawChapter: String,
        configChapter: String,
        currentUrl: String,
        manualCounter: Int? = null
    ): String {
        // @指定の手動カウンター優先
        if (manualCounter != null) {
            return manualCounter.toString().padStart(PAD_LENGTH, '0')
        }

        var chapter = rawChapter

        // 自動取得: JS結果が空ならURLから数字を推測
        if (chapter.isEmpty()) {
            val matches = NUMBER_REGEX.findAll(currentUrl).map { it.value }.toList()
            if (matches.isNotEmpty()) chapter = matches.last()
        }

        // 数字のみ抽出して0埋め
        if (chapter.isNotEmpty()) {
            chapter = chapter.filter { it.isDigit() }.padStart(PAD_LENGTH, '0')
        }

        return chapter
    }

    /**
     * テスト解析用の表示文字列を生成する。
     * 手動開始・推測の注釈を付加する。
     */
    fun extractForDisplay(
        rawChapter: String,
        configChapter: String,
        currentUrl: String
    ): String {
        // @手動指定
        if (configChapter.startsWith("@")) {
            val num = configChapter.substring(1).toIntOrNull()
            return if (num != null) {
                num.toString().padStart(PAD_LENGTH, '0') + " (手動開始)"
            } else {
                rawChapter
            }
        }

        // JS結果が空 → URL推測
        if (rawChapter.isEmpty()) {
            val matches = NUMBER_REGEX.findAll(currentUrl).map { it.value }.toList()
            if (matches.isNotEmpty()) {
                return matches.last().padStart(PAD_LENGTH, '0') + " (推測)"
            }
            return ""
        }

        // 通常
        return rawChapter.filter { it.isDigit() }.padStart(PAD_LENGTH, '0')
    }
}
