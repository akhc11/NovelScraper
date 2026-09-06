package com.example.novelscraper.translation.v2.pipeline

/** ファイル単位の処理状態。中断再開はこの状態＋作業所存在で判定する */
enum class FileState {
    PENDING,
    TRANSLATING,
    FAILED,
    DONE
}

data class FileJob(
    val sourceUri: String,
    val fileName: String,
    val state: FileState = FileState.PENDING
)

/** 検出言語。文字種数え上げによる判定 */
enum class SourceLang {
    ZH,
    KO,
    EN,
    JA
}

data class DetectedLang(val language: SourceLang, val reason: String)

/**
 * 文字種数え上げによる言語判定（pure）。かな・ハングル・漢字・ラテンの比率で決める。
 * 漢字圏の曖昧さはかな／ハングルの有無で分離する。
 */
fun detectLanguage(text: String): DetectedLang {
    var kana = 0
    var hangul = 0
    var han = 0
    var latin = 0
    for (ch in text) {
        when (ch) {
            in '\u3040'..'\u309F', in '\u30A0'..'\u30FF' -> kana++
            in '\uAC00'..'\uD7AF', in '\u1100'..'\u11FF' -> hangul++
            in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF' -> han++
            in 'a'..'z', in 'A'..'Z' -> latin++
        }
    }
    val total = kana + hangul + han + latin
    if (total == 0) return DetectedLang(SourceLang.JA, "empty-fallback-ja")
    return when {
        kana * 2 >= total -> DetectedLang(SourceLang.JA, "kana-dominant")
        hangul * 2 >= total -> DetectedLang(SourceLang.KO, "hangul-dominant")
        hangul > 0 && hangul >= han -> DetectedLang(SourceLang.KO, "hangul-majority")
        kana > 0 -> DetectedLang(SourceLang.JA, "kana-present")
        latin * 2 >= total -> DetectedLang(SourceLang.EN, "latin-dominant")
        han > 0 -> DetectedLang(SourceLang.ZH, "han-only")
        latin > 0 -> DetectedLang(SourceLang.EN, "latin-only")
        else -> DetectedLang(SourceLang.JA, "fallback-ja")
    }
}
