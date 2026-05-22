package daytrader.domain

import daytrader.data.StrategyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreMarketCloseLogicTest {
    @Test
    fun preCloseWindow_startsFiveMinutesBeforeMarketClose() {
        val close = 2_000_000L
        val deadline = PreMarketCloseLogic.positionCloseDeadlineEpochMillis(
            close,
            StrategyCatalog.CLOSE_POSITIONS_BEFORE_MARKET_CLOSE_MIN
        )
        assertEquals(close - 5 * 60_000, deadline)
        assertFalse(
            PreMarketCloseLogic.isWithinPreCloseExitWindow(
                nowEpochMillis = deadline - 1,
                marketCloseEpochMillis = close,
                minutesBeforeClose = 5
            )
        )
        assertTrue(
            PreMarketCloseLogic.isWithinPreCloseExitWindow(
                nowEpochMillis = deadline,
                marketCloseEpochMillis = close,
                minutesBeforeClose = 5
            )
        )
        assertTrue(
            PreMarketCloseLogic.isWithinPreCloseExitWindow(
                nowEpochMillis = close - 1,
                marketCloseEpochMillis = close,
                minutesBeforeClose = 5
            )
        )
        assertFalse(
            PreMarketCloseLogic.isWithinPreCloseExitWindow(
                nowEpochMillis = close,
                marketCloseEpochMillis = close,
                minutesBeforeClose = 5
            )
        )
    }

}
