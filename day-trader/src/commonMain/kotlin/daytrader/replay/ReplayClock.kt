package daytrader.replay

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Virtual wall clock for session replay. [advanceBy] and [advanceTo] move replay time explicitly.
 * [delayMillis] yields without advancing [nowMs] so background engine poll loops cannot drift
 * virtual time past orchestrated milestones (bar close, close-confirmation window, etc.).
 */
class ReplayClock(initialEpochMs: Long) {
    @Volatile
    var nowMs: Long = initialEpochMs
        private set

    fun now(): Long = nowMs

    fun reset(epochMs: Long) {
        nowMs = epochMs
    }

    fun advanceBy(deltaMs: Long) {
        if (deltaMs > 0) nowMs += deltaMs
    }

    fun advanceTo(epochMs: Long) {
        if (epochMs > nowMs) nowMs = epochMs
    }

    suspend fun delayMillis(ms: Long) {
        if (ms <= 0) return
        yield()
        delay(1L)
    }
}
