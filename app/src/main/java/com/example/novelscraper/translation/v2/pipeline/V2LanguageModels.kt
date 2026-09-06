package com.example.novelscraper.translation.v2.pipeline

/**
 * Frequency tables for ingest language evidence (frozen spec values).
 * Old code is not referenced or reused; this file is canonical for v2.
 * Calibration: KO positive 43%, ZH positive 56-69%, JA positive 47-59%.
 * NOTE: this file contains non-ASCII literals by design (frequency data).
 * Byte-level equivalence with the spec source is verified by test.
 */
object V2LanguageModels {

    /** High-frequency Korean syllables (41). */
    val KO_TOP_SYLLABLES: Set<Char> = setOf(
        '은', '는', '이', '가', '을', '를', '에', '의',
        '도', '로', '와', '과', '다', '고', '한', '하',
        '지', '어', '있', '없', '것', '수', '들', '만',
        '나', '너', '말', '일', '라', '마', '바', '사',
        '아', '자', '차', '카', '타', '파', '거', '서',
        '시',
    )

    /** High-frequency Hanzi for Chinese evidence. */
    val ZH_TOP_HANZI: Set<Char> = (
        "的一是不了我不在有人这个上大来和国地到说时要就出会可也你对生能而子那得于着下自之年过发后作里用道" +
            "行所然家种事成方多经么去法学如都同当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实形" +
            "军者意无全日三又关点正业外将两高间由问很最重并物手应战向头文体力气内然长已老么心之乎者也矣焉哉乃" +
            "其若夫曰云尔兮" +
            "這那說國學麼們時個會發現實與對裡萬為麽於眾書長馬鳥魚黃黑龍龜龝"
        ).toSet()

    /** Japanese function words (kana-ratio gate support). */
    val JA_FUNCTION_WORDS: Set<String> = setOf(
        "は", "が", "を", "に", "へ", "で", "の", "も",
        "から", "まで", "より", "です", "ます", "ない", "こと", "これ",
        "それ", "だ", "である", "なり", "たり", "べし", "こそ", "しか",
        "さえ",
    )

    /** Byte frequency tables to tell single-byte family members apart. */
    val SINGLE_BYTE_TABLES: Map<V2CharsetId, IntArray> = mapOf(
        V2CharsetId.W1251 to byteTable(
            0xF0 to 116, 0xE5 to 104, 0xEE to 93, 0xE8 to 69, 0xEA to 69, 0xEB to 58,
            0xF2 to 46, 0xE2 to 46, 0xF1 to 46, 0xEC to 34, 0xE0 to 34, 0xE4 to 34,
            0xEF to 23, 0xED to 23, 0xFF to 23, 0xE7 to 23
        ),
        V2CharsetId.W1256 to byteTable(
            0xC7 to 191, 0xE1 to 150, 0xC8 to 109, 0xD1 to 109, 0xED to 68, 0xE3 to 41,
            0xCA to 41, 0xDA to 41, 0xC9 to 27, 0xCD to 27, 0xD5 to 27, 0xD8 to 27,
            0xE5 to 13, 0xD0 to 13, 0xE4 to 13, 0xCC to 13
        ),
        V2CharsetId.W1253 to byteTable(
            0xE5 to 86, 0xE9 to 86, 0xEF to 86, 0xEA to 86, 0xE1 to 74, 0xF3 to 61,
            0xF4 to 61, 0xED to 61, 0xE7 to 49, 0xFC to 37, 0xEC to 37, 0xDF to 37,
            0xEB to 37, 0xF5 to 24, 0xDD to 24, 0xE4 to 24
        ),
        V2CharsetId.W1255 to byteTable(
            0xE1 to 125, 0xE9 to 107, 0xE5 to 89, 0xE4 to 89, 0xEC to 71, 0xF7 to 71,
            0xE3 to 71, 0xF8 to 71, 0xF2 to 53, 0xED to 35, 0xE6 to 35, 0xE8 to 35,
            0xFA to 35, 0xF9 to 17, 0xF1 to 17, 0xE2 to 17
        ),
        V2CharsetId.TIS620 to byteTable(
            0xD2 to 96, 0xCA to 72, 0xCD to 72, 0xC7 to 60, 0xD1 to 60, 0xC3 to 60,
            0xA1 to 48, 0xB4 to 36, 0xB9 to 36, 0xE8 to 36, 0xB7 to 36, 0xBA to 36,
            0xD5 to 24, 0xA4 to 24, 0xA2 to 24, 0xCB to 24
        ),
        V2CharsetId.W1252 to byteTable(
            0xE9 to 250, 0xFC to 166, 0xE7 to 83, 0xE8 to 83, 0xEF to 83,
            0x9C to 83, 0xDF to 83, 0xC4 to 83, 0xD6 to 83
        ),
        V2CharsetId.W1254 to byteTable(
            0xFC to 466, 0xFD to 199, 0xE7 to 133, 0xFE to 133, 0xDD to 66
        ),
        V2CharsetId.W1258 to byteTable(
            0xE0 to 312, 0xFD to 187, 0xF9 to 124, 0xF4 to 62, 0xEA to 62,
            0xE1 to 62, 0xF5 to 62, 0xF0 to 62, 0xE2 to 62
        )
    )

    private fun byteTable(vararg pairs: Pair<Int, Int>): IntArray {
        val table = IntArray(256)
        for ((byte, weight) in pairs) table[byte] = weight
        return table
    }
}
