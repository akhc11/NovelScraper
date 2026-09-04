package com.example.novelscraper.scraper

import kotlinx.serialization.Serializable

@Serializable
data class ScrapingResult(
    val title: String = "",
    val content: String = "",
    val nextUrl: String = "",
    val chapter: String = "",
    val folderName: String = "",
    val chapterDisplay: String? = null,
    val debugLines: List<DebugLine>? = null
)

@Serializable
data class DebugLine(
    val text: String,
    val selector: String
)
