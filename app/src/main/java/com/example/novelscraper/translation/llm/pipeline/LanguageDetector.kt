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

    /** 分かち書きなし言語の機能語 (部分文字列照合)。同一判定内の重なりは無害 */
    private val KO_WORDS = listOf(
        "은", "는", "이", "가", "을", "를", "에", "의", "도", "로",
        "에서", "하고", "있다", "없다", "이다", "와", "과", "으로"
    )
    private val JA_WORDS = listOf(
        "は", "が", "を", "に", "へ", "で", "の", "も", "から", "まで",
        "より", "です", "ます", "ない", "こと", "これ", "それ", "だ", "である",
        "べし", "なり", "たり", "けり", "こそ", "しか", "さえ", "まし"
    )
    private val ZH_WORDS = listOf(
        "的", "了", "是", "在", "和", "有", "我", "不", "这", "那",
        "就", "都", "与", "对", "能", "会", "个", "中", "上", "而",
        "之", "乎", "者", "也", "矣", "焉", "哉", "乃", "其", "若"
    )
    /** 分かち書き言語の機能語 (小文字化後のトークン完全一致) */
    private val EN_WORDS = setOf(
        "the", "of", "and", "to", "in", "is", "that", "it", "was", "for",
        "on", "are", "as", "with", "his", "they", "at", "be", "have", "from",
        "or", "by", "not", "but", "what", "were", "we", "when"
    )

    private const val MIN_WORD_HITS = 3
    private const val MIN_WORD_DISTINCT = 2
    private const val WORD_MARGIN = 2

    /**
     * 原文テキストから言語（ZH/KO/JA/EN）を自動判定する。
     * 第1判定: 機能語スコアリング (短文・漢字混じりに強い)。
     * 第2判定: 文字種ブロック規則 (ハングル支配ガード付き)。
     * 文字種カウントは品質検証器・ログ用に維持する。
     */
    fun detect(sampleText: String, maxLines: Int = Int.MAX_VALUE): LanguageDetectionResult {
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

        // 1. 機能語スコアリング (確信ある場合のみ確定)
        functionWordWinner(sampleText)?.let {
            return LanguageDetectionResult(
                language = it,
                reason = "機能語確定 漢字:$kanji かな:$kana ハングル:$hangeul",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 2. ZH: 漢字が十分あり、かな比率が低く、ハングルが支配的でない
        if (kanji >= 10 && kana * 10 < kanji && hangeul * 10 < kanji) {
            return LanguageDetectionResult(
                language = SourceLanguage.ZH,
                reason = "漢字:$kanji かな:$kana ハングル:$hangeul",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 3. KO: ハングルが閾値以上
        if (hangeul > 20) {
            return LanguageDetectionResult(
                language = SourceLanguage.KO,
                reason = "ハングル:$hangeul かな:$kana 漢字:$kanji",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 4. JA: かなが閾値以上
        if (kana > 30) {
            return LanguageDetectionResult(
                language = SourceLanguage.JA,
                reason = "かな:$kana 漢字:$kanji",
                kanjiCount = kanji,
                kanaCount = kana,
                hangeulCount = hangeul
            )
        }

        // 5. EN / その他
        return LanguageDetectionResult(
            language = SourceLanguage.EN,
            reason = "ハングル:$hangeul かな:$kana 漢字:$kanji",
            kanjiCount = kanji,
            kanaCount = kana,
            hangeulCount = hangeul
        )
    }

    /**
     * 機能語の最有力言語を返す。総ヒット数・異なり語数・次点比の
     * すべてを満たす場合のみ確定し、単発ヒットの誤爆 (共有語) を防ぐ。
     */
    private fun functionWordWinner(text: String): SourceLanguage? {
        val scores = listOf(
            SourceLanguage.KO to countSubstrings(text, KO_WORDS),
            SourceLanguage.JA to countSubstrings(text, JA_WORDS),
            SourceLanguage.ZH to countSubstrings(text, ZH_WORDS),
            SourceLanguage.EN to countTokens(text, EN_WORDS)
        ).sortedByDescending { it.second.first }

        val (language, best) = scores[0]
        val runnerUp = scores[1].second.first
        if (best.first >= MIN_WORD_HITS && best.second >= MIN_WORD_DISTINCT &&
            best.first >= runnerUp * WORD_MARGIN
        ) {
            return language
        }
        return null
    }

    /** 部分文字列照合 (分かち書きなし言語用)。戻り値は (総ヒット数, 異なり語数) */
    private fun countSubstrings(text: String, words: List<String>): Pair<Int, Int> {
        var hits = 0
        var distinct = 0
        for (word in words) {
            var count = 0
            var index = text.indexOf(word)
            while (index >= 0) {
                count++
                index = text.indexOf(word, index + word.length)
            }
            if (count > 0) {
                hits += count
                distinct++
            }
        }
        return hits to distinct
    }

    /** トークン完全一致 (分かち書き言語用)。1文字トークンは除外 */
    private fun countTokens(text: String, words: Set<String>): Pair<Int, Int> {
        val seen = mutableSetOf<String>()
        var hits = 0
        for (token in text.lowercase().split(Regex("[^a-z]+"))) {
            if (token.length >= 2 && token in words) {
                hits++
                seen.add(token)
            }
        }
        return hits to seen.size
    }
}