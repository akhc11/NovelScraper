package com.example.novelscraper.translation.v2.domain

/**
 * プロバイダー識別子。文字列リテラル分散によるtypo・分岐漏れを型で防ぐ。
 * 記録形式（DataStore JSON）は String のままにし、境界で parse する。
 */
enum class ProviderId(val id: String) {
    GEMINI("gemini"),
    OPENROUTER("openrouter");

    companion object {
        fun parse(raw: String?): ProviderId? =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() }
    }
}

/** 記録文字列→型付きの橋渡し。未知値は null（寛容素通し・送信層で最終判断）。 */
fun String.toProviderId(): ProviderId? = ProviderId.parse(this)
