package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.common.ingest.LanguageModels


/** 呼出毎コンパイルを避けるための共有正規表現 */
private val EN_WORD_REGEX = Regex("""\b[a-zA-Z]{2,}\b""")

/** 検出言語。文字種数え上げによる判定 */
enum class SourceLang {
    ZH,
    KO,
    EN,
    JA
}

data class DetectedLang(val language: SourceLang, val reason: String)

/**
 * 5分岐ヒューリスティック言語判定（pure）。
 * 文字種スクリプト比率に加え、簡体字・韓国語高頻度音節・日本語機能語/国字・英語ストップワード等の
 * 決定論的キー特徴を複合評価することで、目次・顔文字・英字ステータス画面による誤判定を抑止する（完全排除は保証しない）。
 */
fun detectLanguage(text: String): DetectedLang {
    var kana = 0
    var hangul = 0
    var han = 0
    var latin = 0
    var pureSimplified = 0
    var zhParticles = 0
    var jaKokuji = 0
    var koSyllables = 0

    for (ch in text) {
        when {
            (ch in '\u3040'..'\u309F') || (ch in '\u30A0'..'\u30FF') -> kana++
            (ch in '\uAC00'..'\uD7AF') || (ch in '\u1100'..'\u11FF') -> {
                hangul++
                if (ch in LanguageModels.KO_TOP_SYLLABLES) koSyllables++
            }
            (ch in '\u4E00'..'\u9FFF') || (ch in '\u3400'..'\u4DBF') -> {
                han++
                if (ch in LanguageModels.PURE_SIMPLIFIED_CHARS) pureSimplified++
                if (ch in LanguageModels.ZH_PARTICLES) zhParticles++
                if (ch in LanguageModels.JA_KOKUJI) jaKokuji++
            }
            (ch in 'a'..'z') || (ch in 'A'..'Z') -> latin++
        }
    }
    val total = kana + hangul + han + latin
    if (total == 0) return DetectedLang(SourceLang.JA, "empty-fallback-ja")

    // 1. 韓国語判定: ハングルは韓国語固有の強力なシグナル
    if (hangul * 2 >= total) return DetectedLang(SourceLang.KO, "hangul-dominant")
    if (hangul >= 5 && hangul * 10 >= total) return DetectedLang(SourceLang.KO, "hangul-significant")
    if (hangul >= 3 && koSyllables >= 2) return DetectedLang(SourceLang.KO, "hangul-syllables")
    if (hangul > 0 && hangul >= han) return DetectedLang(SourceLang.KO, "hangul-majority")

    // 2. 中国語確定判定: 簡体字または中国語助詞（的/了/在等）が存在し、日本語の助詞がない
    val hasPureSimplified = pureSimplified >= 2 || (pureSimplified >= 1 && zhParticles >= 1)
    val hasZhGrammar = (zhParticles >= 3 && han >= 10) || (zhParticles >= 2 && kana == 0 && han >= 5)
    if (han > 0 && (hasPureSimplified || hasZhGrammar) && kana < 5) {
        return DetectedLang(SourceLang.ZH, if (hasPureSimplified) "zh-simplified" else "zh-particles")
    }

    // 3. 日本語判定: かなが十分にある、または日本語機能語/国字が存在（顔文字1文字ノイズを遮断）
    val hasJaFunctionWord = if (kana >= 2) {
        LanguageModels.JA_FUNCTION_WORDS.any { text.contains(it) }
    } else false
    val isJaGrammar = (kana >= 5 && kana * 50 >= total) || (kana >= 2 && hasJaFunctionWord)
    if (kana * 2 >= total) return DetectedLang(SourceLang.JA, "kana-dominant")
    if (isJaGrammar) return DetectedLang(SourceLang.JA, "kana-grammar")
    if (jaKokuji >= 2 && kana > 0) return DetectedLang(SourceLang.JA, "ja-kokuji")

    // 4. 英語判定: ラテン文字過半数かつ英語ストップワード検証（ステータス画面HP/MP等の誤爆遮断）
    if (latin * 2 >= total) {
        val enWordHits = if (latin >= 10) {
            EN_WORD_REGEX.findAll(text.lowercase())
                .count { it.value in LanguageModels.EN_STOP_WORDS }
        } else 0
        if (enWordHits >= 2 || text.length < 50) {
            return DetectedLang(SourceLang.EN, "latin-stopwords")
        }
    }

    // 5. フォールバック（短文や境界ケース）
    return when {
        han > 0 && kana == 0 -> DetectedLang(SourceLang.ZH, "han-only")
        kana > 0 -> DetectedLang(SourceLang.JA, "kana-present")
        latin * 2 >= total -> DetectedLang(SourceLang.EN, "latin-dominant")
        han > 0 -> DetectedLang(SourceLang.ZH, "han-fallback")
        latin > 0 -> DetectedLang(SourceLang.EN, "latin-fallback")
        else -> DetectedLang(SourceLang.JA, "fallback-ja")
    }
}

