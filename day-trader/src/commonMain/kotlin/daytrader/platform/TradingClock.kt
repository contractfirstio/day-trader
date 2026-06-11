package daytrader.platform

import kotlinx.coroutines.delay

/**
 * Injectable time source for strategy/session logic (bar close, stop rules, pipeline UI).
 *
 * Live broker modes use [WallClock]. Replay uses [daytrader.replay.ReplayClock].
 * Diagnostic logs ([daytrader.diagnostics.LogTimestamps]) stay on wall time.
 */
interface TradingClock {
    fun nowEpochMillis(): Long
    suspend fun delayMillis(ms: Long)
}

/** Real wall clock for IB, hybrid, and emulator modes. */
object WallClock : TradingClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override suspend fun delayMillis(ms: Long) {
        if (ms > 0) delay(ms)
    }
}

/** Replay-only: explicit control of virtual session time. */
interface MutableTradingClock : TradingClock {
    fun reset(epochMs: Long)
    fun advanceBy(deltaMs: Long)
    fun advanceTo(epochMs: Long)
}
