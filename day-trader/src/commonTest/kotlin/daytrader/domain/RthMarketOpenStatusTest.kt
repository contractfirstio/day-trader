package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RthMarketOpenStatusTest {
    @Test
    fun usMarket_openDuringRth() {
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!!
        val midday = open + 2 * 60 * 60 * 1000
        assertTrue(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.US, midday))
    }

    @Test
    fun usMarket_closedAfterRth() {
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!!
        val afterClose = open + 7 * 60 * 60 * 1000
        assertFalse(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.US, afterClose))
    }

    @Test
    fun usMarket_closedBeforeOpen() {
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!!
        assertFalse(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.US, open - 60_000))
    }
}
