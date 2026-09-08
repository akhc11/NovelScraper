package com.example.novelscraper.translation.common

object TextCleanser {

    /**
     * 原文テキストに含まれる有害な制御文字 (NULLバイト、C0/C1制御文字等) を安全に除去する。
     * 改行 (\n, \r) およびタブ (\t) は維持する。
     */
    fun cleanse(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder(raw.length)
        for (i in raw.indices) {
            val ch = raw[i]
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> {
                    sb.append(ch)
                }
                ch.isISOControl() || ch == '\u0000' || ch == '\uFEFF' || ch == '\u200B' -> {
                    // NULLバイト、BOM埋め込み、ゼロ幅スペース、制御文字を除去
                    continue
                }
                else -> {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}