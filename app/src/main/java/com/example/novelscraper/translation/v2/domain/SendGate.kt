package com.example.novelscraper.translation.v2.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared minimum send-interval gate (all workers, translation path only).
 *
 * Improvement vs old: the next slot is reserved under lock, so concurrent
 * workers serialize instead of bursting through a timestamp set after the
 * wait. Dictionary generation stays outside the gate (parity with old).
 *
 * Lifetime is the owner's (RunEngine instance ~= app lifetime), same as
 * the old global gate: no reset between runs.
 */
class V2SendGate(
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var nextAllowedMs: Long = 0L

    suspend fun acquire(minIntervalMs: Long) {
        val wait = mutex.withLock {
            val now = clockMs()
            val start = maxOf(now, nextAllowedMs)
            nextAllowedMs = start + minIntervalMs.coerceAtLeast(0)
            start - now
        }
        if (wait > 0) delay(wait)
    }
}
