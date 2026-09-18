package com.example.novelscraper.translation.v2.pipeline

/** 呼出毎コンパイルを避けるための共有正規表現 */
private val BLANK_LINE_COLLAPSE_REGEX = Regex("\n{3,}")

/**
 * 実行時本文の基本クレンジング。\r→\n、ISO制御文字を除去、空行増殖を正規化する。
 * 物理分割前の [cleanseForSplit] とは別物（こちらはBOM/ZWSPを残す実行時用）。
 */
fun cleanseBasic(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        when {
            ch == '\r' -> sb.append('\n')
            ch == '\n' || ch == '\t' -> sb.append(ch)
            ch.isISOControl() -> Unit
            else -> sb.append(ch)
        }
    }
    return sb.toString().replace(BLANK_LINE_COLLAPSE_REGEX, "\n\n")
}
