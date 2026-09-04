package com.example.novelscraper.scraper

import kotlinx.serialization.Serializable

/**
 * テキストから要素を検索した際のヒット結果アイテム
 *
 * @param selector 要素を指定するためのCSSセレクタ
 * @param tag タグ名（例: TITLE, META, H1, DIV など）
 * @param preview 要素内のテキスト内容プレビュー
 * @param matchType 検出種別（例: ページタイトル (<title>), メタデータ, セレクタ直接指定 など）
 */
@Serializable
data class TextSearchResultItem(
    val selector: String,
    val tag: String,
    val preview: String,
    val matchType: String
)
