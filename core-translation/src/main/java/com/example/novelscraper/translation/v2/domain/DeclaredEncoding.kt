package com.example.novelscraper.translation.v2.domain

/**
 * User/folder-declared input charset. Null (or "AUTO") means auto-detect.
 * Lives in domain so settings, pipeline and ui share one definition.
 */
enum class V2DeclaredEncoding {
    CP949, GB18030, BIG5, SJIS, EUC_JP, UTF8, W1252;

    companion object {
        fun parseOrNull(value: String?): V2DeclaredEncoding? {
            if (value == null) return null
            return try {
                if (value == "AUTO") null else valueOf(value)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Input-charset choices for the settings UI (first entry AUTO = auto-detect). */
val V2_ENCODING_OPTIONS: List<Pair<String, String>> = listOf(
    "AUTO" to "AUTO(detect)",
    "CP949" to "CP949(Korean)",
    "GB18030" to "GB18030(Chinese)",
    "BIG5" to "Big5(Traditional)",
    "SJIS" to "Shift_JIS(JP)",
    "EUC_JP" to "EUC-JP(JP)",
    "UTF8" to "UTF-8",
    "W1252" to "windows-1252(Western)"
)
