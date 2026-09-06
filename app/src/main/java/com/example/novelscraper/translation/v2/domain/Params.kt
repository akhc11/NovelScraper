package com.example.novelscraper.translation.v2.domain

/**
 * 値解決の単一則：「プリセット既定＜ユーザー上書き」、空＝未指定（未送信）。
 * 範囲外は能力範囲に丸めて警告対象にする。pure・副作用なし。
 */
data class Resolved<T>(val value: T, val coerced: Boolean)

/** 選択肢式（thinkingLevel等）。非対応なら何が来ても未指定に落とす */
fun resolveOption(allowedValues: Set<String>?, preset: String?, override: String?): String? {
    val chosen = override?.takeIf { it.isNotBlank() }
        ?: preset?.takeIf { it.isNotBlank() }
        ?: return null
    if (allowedValues != null && chosen !in allowedValues) return null
    return chosen
}

/** 数値式。範囲外は丸める */
fun resolveDouble(range: SamplingParam?, preset: Double?, override: Double?): Resolved<Double?> {
    val chosen = override ?: preset ?: return Resolved(null, false)
    if (range == null) return Resolved(chosen, false)
    val fixed = chosen.coerceIn(range.min, range.max)
    return Resolved(fixed, fixed != chosen)
}

/** 整数式。範囲外は丸める */
fun resolveInt(range: IntRange?, preset: Int?, override: Int?): Resolved<Int?> {
    val chosen = override ?: preset ?: return Resolved(null, false)
    if (range == null) return Resolved(chosen, false)
    val fixed = chosen.coerceIn(range)
    return Resolved(fixed, fixed != chosen)
}
