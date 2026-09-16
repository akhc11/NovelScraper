package com.example.novelscraper.translation.v2.domain

import kotlin.math.pow
import kotlin.random.Random

/**
 * 検証・解決・表示で共有する調整値の単一真実。
 * 技術的根拠1行：同じ数値の二重実装は必ず乖離するため、参照はここに寄せる（値は従来通り）。
 */
object TranslationLimits {
    const val MIN_OUTPUT_TOKENS = 1000
    val WORKER_COUNT_RANGE = 1..6
    val DICT_WORKER_RANGE = 1..30
    val DICT_CONCURRENCY_RANGE = 1..10
    val DICT_BATCH_BYTES_RANGE = 4000..200000
    val DICT_PARALLELISM_RANGE = 1..30
    const val SPLIT_MIN_CHARS = 500
    val PREV_LINES_RANGE = 1..100
    val PROMPT_NUMBER_RANGE = 1..7
    val SIZE_RATIO_RANGE = 10..1000
    val OUTPUT_CHARS_RANGE = 2000..100000
    const val COOLDOWN_MIN_SEC = 5
    const val COOLDOWN_MAX_SEC = 300
    const val RETRY_AFTER_MIN_SEC = 1L
    const val RETRY_AFTER_MAX_SEC = 600L
    const val WAIT_MAX_SEC = 120L
    const val UNMANAGED_COOLDOWN_MAX_SEC = 300
    /** 辞書文面の1文あたり上限。超過分は解決時に切り落とし、検証で警告する。 */
    const val MAX_DICT_PROMPT_CHARS = 20000
    /** 本文1件あたりの辞書照合上限（完全一致・別名で共有）。 */
    const val DICT_MATCH_LIMIT = 500
}

/**
 * 再送方針の単一真実。待機時間式を1箇所にし、指数バックオフ＋振動を任意化する。
 * 既定は無振動（現行の決定性とテストの再現性を保つ）。振動が必要な経路だけ比率を指定する。
 * 技術的根拠1行：待機式の二重実装は必ず乖離するため、式はここだけに置く。
 */
data class RetryPolicy(
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 120_000L,
    val factor: Double = 2.0,
    val jitterRatio: Double = 0.0
) {
    /**
     * 試行回（1-based。再送1回目=1）に対応する待機ms。
     * @param random 振動用の乱数源（テストは固定値で決定性を保つ）
     */
    fun delayForAttempt(attempt: Int, random: Random = Random.Default): Long {
        var ms = baseDelayMs.toDouble() * factor.pow(attempt.coerceAtLeast(1) - 1)
        if (ms > maxDelayMs) ms = maxDelayMs.toDouble()
        if (jitterRatio > 0) {
            ms *= 1 + (random.nextDouble() * 2 - 1) * jitterRatio
        }
        return ms.toLong().coerceAtLeast(0L)
    }
}

/** 骨格カーネル既定の再送方針（1秒起点・指数・上限120秒・無振動）。 */
val DefaultRetryPolicy = RetryPolicy()
