package daytrader.data

import daytrader.domain.TouchTurnLogic
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketOpenCountdownWatcherTest {
    @Test
    fun shouldStartCountdown_inNineToTenSecondWindowBeforeOpen() {
        val zone = "America/New_York"
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, null)!!
        assertTrue(shouldStartCountdown(open - 10_000, open))
        assertTrue(shouldStartCountdown(open - 9_500, open))
        assertFalse(shouldStartCountdown(open - 8_000, open))
        assertFalse(shouldStartCountdown(open - 11_000, open))
    }

    private fun shouldStartCountdown(now: Long, open: Long): Boolean {
        val millisUntilOpen = open - now
        return millisUntilOpen in 9_000L..10_999L
    }
}
