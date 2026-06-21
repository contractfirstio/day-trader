package daytrader.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayQuoteSpeedTest {
    @Test
    fun maxSpeed_hasZeroInterval() {
        assertEquals(0L, ReplayQuoteSpeed.MAX_SPEED.intervalMs)
        assertTrue(ReplayQuoteSpeed.MAX_SPEED.isMaxSpeed)
        assertTrue(ReplayQuoteSpeed.isMaxSpeed(0L))
    }

    @Test
    fun closest_mapsZeroToMaxSpeed() {
        assertEquals(ReplayQuoteSpeed.MAX_SPEED, ReplayQuoteSpeed.closest(0L))
    }
}
