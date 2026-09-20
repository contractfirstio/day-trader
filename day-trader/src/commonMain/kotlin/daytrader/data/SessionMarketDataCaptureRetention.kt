package daytrader.data

import daytrader.domain.TouchTurnSessionStopTrigger

/**
 * Whether [SessionMarketDataCapture] should outlive a stopped Touch Turn session so
 * `prices.jsonl` keeps appending until the RTH open + [stopAfterOpenMinutes] cutoff
 * (replay tape for alternate rule scenarios).
 */
object SessionMarketDataCaptureRetention {
    fun retainAfterSessionStop(trigger: TouchTurnSessionStopTrigger): Boolean =
        when (trigger) {
            TouchTurnSessionStopTrigger.OPEN_DEADLINE,
            TouchTurnSessionStopTrigger.GLOBAL_KILL_SWITCH,
            TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN -> false
            else -> true
        }

    fun captureDeadlineEpochMillis(sessionOpenEpochMillis: Long, stopAfterOpenMinutes: Int): Long =
        sessionOpenEpochMillis + stopAfterOpenMinutes * 60_000L
}
