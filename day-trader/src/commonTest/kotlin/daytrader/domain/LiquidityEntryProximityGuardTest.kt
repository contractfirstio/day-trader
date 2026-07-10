package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidityEntryProximityGuardTest {
    @Test
    fun shouldSkipResize_whenEntryTouchable() {
        assertTrue(LiquidityEntryProximityGuard.shouldSkipResize(entryTouchable = true))
    }

    @Test
    fun shouldNotSkipResize_whenNotTouchableOrUnknown() {
        assertFalse(LiquidityEntryProximityGuard.shouldSkipResize(entryTouchable = false))
        assertFalse(LiquidityEntryProximityGuard.shouldSkipResize(entryTouchable = null))
    }
}
