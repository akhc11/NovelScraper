package com.example.novelscraper.translation.v2.domain

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
    val SIZE_RATIO_RANGE = 10..1000
    val OUTPUT_CHARS_RANGE = 2000..100000
    const val COOLDOWN_MIN_SEC = 5
    const val COOLDOWN_MAX_SEC = 300
    const val RETRY_AFTER_MIN_SEC = 1L
    const val RETRY_AFTER_MAX_SEC = 600L
    const val WAIT_MIN_SEC = 5L
    const val WAIT_MAX_SEC = 120L
    const val UNMANAGED_COOLDOWN_MAX_SEC = 300
}
