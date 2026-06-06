package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchlistEntryProximityEvaluatorTest {
    @Test
    fun percentThreshold_marksNearWhenWithinDistance() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            entryPrice = 100.0,
            proximityAlertEnabled = true,
            proximityThresholdMode = ProximityThresholdMode.PERCENT,
            proximityThresholdValue = 1.0
        )

        val near = WatchlistEntryProximityEvaluator.evaluatePlan(plan, 100.5)
        val far = WatchlistEntryProximityEvaluator.evaluatePlan(plan, 102.0)

        assertTrue(near?.isNear == true)
        assertEquals(0.5, near?.distance)
        assertFalse(far?.isNear == true)
    }

    @Test
    fun absoluteThreshold_marksNearWhenWithinDistance() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            entryPrice = 185.0,
            proximityAlertEnabled = true,
            proximityThresholdMode = ProximityThresholdMode.ABSOLUTE,
            proximityThresholdValue = 2.0
        )

        val near = WatchlistEntryProximityEvaluator.evaluatePlan(plan, 186.5)
        assertTrue(near?.isNear == true)
    }

    @Test
    fun disabledPlan_isIgnored() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            entryPrice = 100.0,
            proximityAlertEnabled = false,
            proximityThresholdValue = 1.0
        )

        assertEquals(null, WatchlistEntryProximityEvaluator.evaluatePlan(plan, 100.1))
    }

    @Test
    fun placedOrderPlan_isIgnored() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            entryPrice = 100.0,
            proximityAlertEnabled = true,
            proximityThresholdMode = ProximityThresholdMode.PERCENT,
            proximityThresholdValue = 1.0,
            orderPlacedAtEpochMs = 1_700_000_000_000L,
            placedOrderIds = listOf(100, 101, 102)
        )

        assertEquals(null, WatchlistEntryProximityEvaluator.evaluatePlan(plan, 100.5))
    }
}
