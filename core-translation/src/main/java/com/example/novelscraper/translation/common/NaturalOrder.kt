package com.example.novelscraper.translation.common

/**
 * 自然順比較の単一正本（01, 02, ... 10 を数値として扱う）。
 * 技術的根拠1行：表示順・範囲選択順・実行順の正本を1箇所に寄せ、picker/web/v2エンジン間の判定式乖離を物理的に防ぐ。
 */
private val TOKEN_REGEX = Regex("(\\d+)|(\\D+)")

fun compareNatural(a: String, b: String): Int {
    val aTokens = TOKEN_REGEX.findAll(a).map { it.value }.toList()
    val bTokens = TOKEN_REGEX.findAll(b).map { it.value }.toList()
    val minLen = minOf(aTokens.size, bTokens.size)

    for (i in 0 until minLen) {
        val aToken = aTokens[i]
        val bToken = bTokens[i]
        val aNum = aToken.toLongOrNull()
        val bNum = bToken.toLongOrNull()

        if (aNum != null && bNum != null) {
            val cmp = aNum.compareTo(bNum)
            if (cmp != 0) return cmp
        } else {
            val cmp = aToken.compareTo(bToken, ignoreCase = true)
            if (cmp != 0) return cmp
        }
    }
    return aTokens.size.compareTo(bTokens.size)
}
