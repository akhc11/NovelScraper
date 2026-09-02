package com.example.novelscraper.translation.llm.pipeline

enum class SourceLanguage(val code: String, val displayName: String) {
    ZH("ZH", "中国語"),
    KO("KO", "韓国語"),
    JA("JA", "日本語"),
    EN("EN", "英語 / その他")
}

data class LanguageDetectionResult(
    val language: SourceLanguage,
    val reason: String,
    val kanjiCount: Int,
    val kanaCount: Int,
    val hangeulCount: Int
)

object LanguageDetector {

    /**
     * 原文テキストの先頭サンプル行から言語（ZH/KO/JA/EN）を自動判定する。
     * スクリプトの AWK 文字コード計数ロジックと100%同一の基準。
     */
    fun detect(sampleText: String, maxLines: Int = 200): LanguageDetectionResult {
        var kanji = 0
        var kana = 0
        var hangeul = 0

        val lines = sampleText.lineSequence().take(maxLines)
        for (line in lines) {
            var i = 0
            while (i < line.length) {
                val codePoint = line.codePointAt(i)
                val charCount = Character.charCount(codePoint)

                when {
                    // ひらがな (U+3040 - U+309F) / カタカナ (U+30A0 - U+30FF)
                    codePoint in 0x3040..0x30FF -> kana++
                    // ハングル字母 (U+1100 - U+11FF) / ハングル音節 (U+AC00 - U+D7A3)
                    codePoint in 0x1100..0x11FF || codePoint in 0xAC00..0xD7A3 -> hangeul++
                    // CJK統合漢字 (U+4E00 - U+9FFF)
                    codePoint in 0x4E00..0x9FFF -> kanji++
                }
                i += charCount
            }
        }

        // 1. ZH: 漢字が十分あり、かな比率が低い (KOより先に判定)
        if (kanji >= 10 && kana * 10 < kanji) {
            return LanguageDetectionResult(
                language = SourceLanguage.ZH,
                reason = "漢字:$kanji かな:$kana ハングル:$hangeul",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 2. KO: ハングルが閾値以上
        if (hangeul > 20) {
            return LanguageDetectionResult(
                language = SourceLanguage.KO,
                reason = "ハングル:$hangeul かな:$kana 漢字:$kanji",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 3. JA: かなが閾値以上
        if (kana > 30) {
            return LanguageDetectionResult(
                language = SourceLanguage.JA,
                reason = "かな:$kana 漢字:$kanji",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 4. EN / その他
        return LanguageDetectionResult(
            language = SourceLanguage.EN,
            reason = "ハングル:$hangeul かな:$kana 漢字:$kanji",
            kanjiCount = kanji,
            kanaCount = kana,
            hangeulCount = hangeul
        )
    }
}