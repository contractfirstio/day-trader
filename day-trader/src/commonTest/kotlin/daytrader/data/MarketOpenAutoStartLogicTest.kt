package daytrader.data

import daytrader.domain.TouchTurnLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarketOpenAutoStartLogicTest {
    @Test
    fun sessionDateIfMarketOpen_nullBeforeUsRthOpen() {
        val zone = "America/New_York"
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, null)!!
        assertNull(MarketOpenAutoStartLogic.sessionDateIfMarketOpen(zone, open - 60_000))
    }

    @Test
    fun sessionDateIfMarketOpen_returnsDateAtAndAfterUsOpen() {
        val zone = "America/New_York"
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, null)!!
        assertEquals("2026-05-22", MarketOpenAutoStartLogic.sessionDateIfMarketOpen(zone, open))
        assertEquals("2026-05-22", MarketOpenAutoStartLogic.sessionDateIfMarketOpen(zone, open + 3_600_000))
    }

    @Test
    fun sessionDateIfMarketOpen_catchesLateAppLaunchAfterOpen() {
        val zone = "America/New_York"
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, null)!!
        // App opened 2 hours after RTH open — still same session day.
        assertEquals("2026-05-22", MarketOpenAutoStartLogic.sessionDateIfMarketOpen(zone, open + 7_200_000))
    }
}
