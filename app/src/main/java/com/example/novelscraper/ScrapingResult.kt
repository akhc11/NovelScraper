package com.example.novelscraper

import kotlinx.serialization.Serializable

@Serializable
data class ScrapingResult(
    val title: String = "",
    val content: String = "",
    val nextUrl: String = "",
    val chapter: String = "",
    val folderName: String = ""
)
