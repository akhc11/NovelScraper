package com.example.novelscraper

import java.net.URI

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

        // 自動取得: JS結果が空ならURLのパス部分から数字を推測
        if (chapter.isEmpty()) {
            val path = try {
                URI(currentUrl).path ?: ""
            } catch (e: Exception) {
                ""
            }
            chapter = NUMBER_REGEX.findAll(path).lastOrNull()?.value ?: ""
        }

        // 数字のみ抽出して0埋め（数字が全く含まれない場合は空文字にして "0000" を防ぐ）
        if (chapter.isNotEmpty()) {
            val digits = chapter.filter { it.isDigit() }
            chapter = if (digits.isNotEmpty()) {
                digits.padStart(PAD_LENGTH, '0')
            } else {
                ""
            }
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

        // JS結果が空 → URLのパス部分から数字を推測
        if (rawChapter.isEmpty()) {
            val path = try {
                URI(currentUrl).path ?: ""
            } catch (e: Exception) {
                ""
            }
            val lastMatch = NUMBER_REGEX.findAll(path).lastOrNull()?.value ?: ""
            val lastNum = lastMatch.filter { it.isDigit() }
            return if (lastNum.isNotEmpty()) {
                lastNum.padStart(PAD_LENGTH, '0') + " (推測)"
            } else {
                ""
            }
        }

        // 通常（数字が全く含まれない場合は空文字にして "0000" を防ぐ）
        val digits = rawChapter.filter { it.isDigit() }
        return if (digits.isNotEmpty()) {
            digits.padStart(PAD_LENGTH, '0')
        } else {
            ""
        }
    }
}
