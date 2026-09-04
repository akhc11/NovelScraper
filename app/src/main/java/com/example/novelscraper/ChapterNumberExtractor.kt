package com.example.novelscraper

import java.net.URI

/**
 * チャプター番号の抽出・整形ロジックを一元管理する。
 * MainActivity.performTestRun と ScrapingTask.processPage で共通利用。
 */
object ChapterNumberExtractor {

    private const val DEFAULT_PAD_LENGTH = 4
    private val NUMBER_REGEX = Regex("(\\d+)")

    /**
     * 手動指定（@連番）のパース結果
     * @param startNumber 開始番号
     * @param padLength パディング桁数（0 = パディングなし、例: @1# や @1:raw）
     */
    data class ManualSpec(
        val startNumber: Int,
        val padLength: Int
    )

    /**
     * 全角英数・記号（＠、０〜９）を半角に正規化し、空白をトリムする
     */
    fun normalize(input: String): String {
        return input.trim()
            .replace('＠', '@')
            .map { c ->
                if (c in '０'..'９') {
                    (c - '０' + '0'.code).toChar()
                } else {
                    c
                }
            }.joinToString("")
    }

    /**
     * configChapter から手動連番指定（@1, @01, @1#, セレクタ || @1 など）を安全にパースする
     */
    fun parseManualSpec(configChapter: String): ManualSpec? {
        val norm = normalize(configChapter)
        if (norm.isEmpty()) return null

        // ハイブリッド指定 (例: ".chapter || @1") の場合、"||" の後ろを取得
        val manualPart = if (norm.contains("||")) {
            norm.substringAfter("||").trim()
        } else {
            norm
        }

        if (!manualPart.startsWith("@")) return null
        val body = manualPart.substring(1).trim()
        if (body.isEmpty() || body.equals("URL", ignoreCase = true)) return null

        // パディングなしモード（例: @1# または @1:raw）
        val rawMatch = Regex("^(\\d+)(#|:raw)$").find(body)
        if (rawMatch != null) {
            val num = rawMatch.groupValues[1].toIntOrNull() ?: return null
            return ManualSpec(num, 0)
        }

        // 先頭0指定（例: @01 -> 2桁, @001 -> 3桁）
        if (body.startsWith("0") && body.all { it.isDigit() }) {
            val num = body.toIntOrNull() ?: return null
            return ManualSpec(num, body.length)
        }

        // 通常指定（例: @1, @100） -> デフォルト4桁パディング
        val num = body.toIntOrNull() ?: return null
        return ManualSpec(num, DEFAULT_PAD_LENGTH)
    }

    /**
     * 数値を指定のパディング桁数で文字列化する
     */
    fun formatNumber(number: Int, padLength: Int): String {
        return if (padLength > 0) {
            number.toString().padStart(padLength, '0')
        } else {
            number.toString()
        }
    }

    /**
     * スクレイピング結果とコンフィグからチャプター番号文字列を決定する。
     *
     * @param rawChapter JS から返されたチャプター文字列
     * @param configChapter ユーザ設定の chapter フィールド（@付きで手動指定、またはセレクタ）
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
        val spec = parseManualSpec(configChapter)
        val padLength = spec?.padLength ?: DEFAULT_PAD_LENGTH

        // 1. 手動カウンター優先（@指定時）
        if (manualCounter != null) {
            return formatNumber(manualCounter, padLength)
        }

        var chapter = rawChapter.trim()

        // 2. 自動取得: JS結果が空ならURLのパス部分から数字を推測
        if (chapter.isEmpty()) {
            val path = try {
                URI(currentUrl).path ?: ""
            } catch (e: Exception) {
                ""
            }
            chapter = NUMBER_REGEX.findAll(path).lastOrNull()?.value ?: ""
        }

        // 3. 数字のみ抽出してパディング（数字が全く含まれない場合は空文字にして "0000" を防ぐ）
        if (chapter.isNotEmpty()) {
            val digits = chapter.filter { it.isDigit() }
            chapter = if (digits.isNotEmpty()) {
                val num = digits.toIntOrNull()
                if (num != null) {
                    formatNumber(num, padLength)
                } else {
                    digits.padStart(padLength, '0')
                }
            } else {
                ""
            }
        }

        return chapter
    }

    /**
     * テスト解析用の表示文字列を生成する。
     * 手動開始・推測・ハイブリッド等の注釈を付加する。
     */
    fun extractForDisplay(
        rawChapter: String,
        configChapter: String,
        currentUrl: String
    ): String {
        val spec = parseManualSpec(configChapter)
        val norm = normalize(configChapter)
        val isHybrid = norm.contains("||")

        // 手動連番指定がある場合
        if (spec != null) {
            val startFormatted = formatNumber(spec.startNumber, spec.padLength)
            val nextFormatted = formatNumber(spec.startNumber + 1, spec.padLength)

            // ハイブリッド指定でDOMから数字が取れている場合
            if (isHybrid && rawChapter.isNotBlank()) {
                val rawDigits = rawChapter.filter { it.isDigit() }
                if (rawDigits.isNotEmpty()) {
                    val num = rawDigits.toIntOrNull()
                    val formatted = if (num != null) formatNumber(num, spec.padLength) else rawDigits.padStart(spec.padLength, '0')
                    return "$formatted (セレクタ抽出 / 連番待機: $startFormatted)"
                }
            }

            return "$startFormatted (連番開始: 次回 $nextFormatted)"
        }

        // JS結果が空 → URLのパス部分から数字を推測
        if (rawChapter.isBlank()) {
            val path = try {
                URI(currentUrl).path ?: ""
            } catch (e: Exception) {
                ""
            }
            val lastMatch = NUMBER_REGEX.findAll(path).lastOrNull()?.value ?: ""
            val lastNum = lastMatch.filter { it.isDigit() }
            return if (lastNum.isNotEmpty()) {
                lastNum.padStart(DEFAULT_PAD_LENGTH, '0') + " (推測)"
            } else {
                ""
            }
        }

        // 通常（数字が全く含まれない場合は空文字にして "0000" を防ぐ）
        val digits = rawChapter.filter { it.isDigit() }
        return if (digits.isNotEmpty()) {
            digits.padStart(DEFAULT_PAD_LENGTH, '0')
        } else {
            ""
        }
    }
}
