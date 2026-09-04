package com.example.novelscraper.translation.llm.api

import kotlin.math.min
import kotlin.random.Random

/**
 * 503/502/504 等の一時的サーバー障害に対する指数バックオフ＋ジッター。
 *
 * - 試行回数に応じて 5s → 10s → 20s → 40s → 上限60s と倍増させる。
 * - 固定間隔だと複数ワーカーが同時再送して再び503を踏むため、
 *   0〜1秒の乱数ジッターを足して再送時刻を散らす。
 * - 401/403/404等の致命的エラーや品質エラーには使わないこと。
 */
object LlmRetryPolicy {
    const val BASE_DELAY_MS = 5000L
    const val MAX_DELAY_MS = 60000L
    const val JITTER_MS = 1000L

    /**
     * 分間制限疑いの429で同モデル再試行する上限回数。
     * これを超えたら従来通りモデル/キーローテーションへ進む。
     */
    const val MAX_SAME_MODEL_QUOTA_RETRIES = 2

    /** 同モデル再試行の待機秒数の下限・上限 */
    const val MIN_QUOTA_WAIT_SEC = 5
    const val MAX_QUOTA_WAIT_SEC = 120

    fun backoffDelayMs(
        retry: Int,
        baseMs: Long = BASE_DELAY_MS,
        maxMs: Long = MAX_DELAY_MS,
        jitterMs: Long = JITTER_MS,
        random: Random = Random.Default
    ): Long {
        val safeRetry = retry.coerceAtLeast(0).coerceAtMost(10)
        val exponential = baseMs * (1L shl safeRetry)
        val capped = min(exponential, maxMs)
        val jitter = if (jitterMs > 0) random.nextLong(0, jitterMs + 1) else 0L
        return capped + jitter
    }

    /**
     * 分間制限疑いの429における同モデル再試行の待機秒数。
     * サーバー指定の retryDelay を優先し、なければ30秒。5〜120秒に丸める。
     */
    fun quotaSameModelWaitSec(retryAfterSec: Int): Long {
        return retryAfterSec.toLong().coerceIn(MIN_QUOTA_WAIT_SEC.toLong(), MAX_QUOTA_WAIT_SEC.toLong())
    }
}
