package com.example.novelscraper.scraper

/**
 * 除外セレクタ文字列（カンマ+スペース結合仕様）の純粋な変換処理。
 * ViewModel から切り出して単体テスト可能にしたもの。既存実装と挙動完全同等:
 * - カンマ区切りで分割し、各要素をtrim、空要素は除去
 * - 重複は排除（完全一致）
 * - 結合は「カンマ+スペース」
 */
object ExcludeSelectorCodec {

    /**
     * 既存リストに selector を追加する（呼び出し側で selector 非空を保証すること）。
     * 重複する場合は追加せず正規化のみ。
     */
    fun merge(current: String, selector: String): String {
        val list = parse(current)
        return if (list.contains(selector)) {
            list.joinToString(SEPARATOR)
        } else {
            (list + selector).joinToString(SEPARATOR)
        }
    }

    /** 指定セレクタを除去する。存在しない場合は正規化のみ行う。 */
    fun remove(current: String, selector: String): String =
        parse(current).filter { it != selector }.joinToString(SEPARATOR)

    private const val SEPARATOR = ", "

    private fun parse(current: String): List<String> =
        current.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
