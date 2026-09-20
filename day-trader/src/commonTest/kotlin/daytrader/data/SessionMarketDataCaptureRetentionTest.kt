package daytrader.data

import daytrader.domain.TouchTurnSessionStopTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionMarketDataCaptureRetentionTest {
    @Test
    fun retainAfterSessionStop_keepsCaptureForNoTradeAndManualStops() {
        assertTrue(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.NO_TRADE_DECISION))
        assertTrue(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.MANUAL))
        assertTrue(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN))
        assertTrue(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.ERROR))
        assertTrue(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.PRE_MARKET_CLOSE))
        assertTrue(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.REPLAY_QUOTES_EXHAUSTED))
    }

    @Test
    fun retainAfterSessionStop_releasesCaptureAtDeadlineKillAndShutdown() {
        assertFalse(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.OPEN_DEADLINE))
        assertFalse(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.GLOBAL_KILL_SWITCH))
        assertFalse(SessionMarketDataCaptureRetention.retainAfterSessionStop(TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN))
    }

    @Test
    fun captureDeadlineEpochMillis_usesConfiguredMinutesAfterOpen() {
        val open = 1_000_000L
        assertEquals(open + 90 * 60_000L, SessionMarketDataCaptureRetention.captureDeadlineEpochMillis(open, 90))
        assertEquals(open + 5 * 60_000L, SessionMarketDataCaptureRetention.captureDeadlineEpochMillis(open, 5))
    }
}
