package daytrader.replay

import daytrader.platform.MutableTradingClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Virtual wall clock for session replay. [advanceBy] and [advanceTo] move replay time explicitly.
 * [delayMillis] advances virtual time so engine settle/retry waits can reach bar-close milestones.
 * Background poll loops in replay should use wall [delay], not [delayMillis], so only orchestrator
 * steps and intentional settle waits move session time.
 */
class ReplayClock(initialEpochMs: Long) : MutableTradingClock {
    @Volatile
    private var nowMs: Long = initialEpochMs

    override fun nowEpochMillis(): Long = nowMs

    override fun reset(epochMs: Long) {
        nowMs = epochMs
    }

    override fun advanceBy(deltaMs: Long) {
        if (deltaMs > 0) nowMs += deltaMs
    }

    override fun advanceTo(epochMs: Long) {
        if (epochMs > nowMs) nowMs = epochMs
    }

    override suspend fun delayMillis(ms: Long) {
        if (ms <= 0) return
        advanceBy(ms)
        yield()
        delay(1L)
    }
}
