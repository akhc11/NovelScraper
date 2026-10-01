package com.example.novelscraper.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class ScraperConfig(
    val body: String = "",
    val title: String = "",
    val fileRegex: String = "",
    val folder: String = "",
    val folderLink: String = "",
    val regex: String = "",
    val next: String = "",
    val chapter: String = "",
    val chapterRegex: String = "",
    val delay: String = "15-30",
    val endCheck: String = "list|index|toc|javascript|null",
    val autoUrl: String = "",
    val exclude: String = "",
    // 保存先ルート名（Downloads/<saveDir>/<作品名>/）。空欄時は従来のNovelScraper。
    val saveDir: String = ""
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun fromJson(json: String): ScraperConfig {
            return try {
                jsonParser.decodeFromString(json)
            } catch (e: Exception) {
                ScraperConfig()
            }
        }
    }
}

enum class SelectorField(val displayName: String) {
    BODY("本文"),
    TITLE("タイトル"),
    NEXT("次ページ"),
    CHAPTER("チャプター番号"),
    FOLDER("作品名"),
    FOLDER_LINK("別URL取得"),
    EXCLUDE("除外要素")
}
