package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchlistBracketOrderPlannerTest {
    @Test
    fun buildTouchTurnPlan_longBracketHasThreeLegs() {
        val result = WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = "AAPL",
            currencyCode = "USD",
            instrument = null,
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
            quantity = 10
        )
        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals("AAPL", plan.symbol)
        assertEquals(TouchTurnTradeSide.LONG, plan.side)
        assertEquals(3, plan.orders.size)
        assertEquals(TouchTurnOrderRole.ENTRY, plan.orders[0].role)
        assertEquals("BUY", plan.orders[0].action)
        assertEquals("LMT", plan.orders[0].orderType)
        assertEquals(TouchTurnOrderRole.TAKE_PROFIT, plan.orders[1].role)
        assertEquals(TouchTurnOrderRole.STOP_LOSS, plan.orders[2].role)
        assertEquals("STP", plan.orders[2].orderType)
        assertEquals(105.0, plan.orders[2].trailTriggerPrice)
    }

    @Test
    fun buildTouchTurnPlan_stopEntryUsesStpParent() {
        val result = WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = "AAPL",
            currencyCode = "USD",
            instrument = null,
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
            quantity = 10,
            options = WatchlistBracketOrderPlanner.BracketOrderOptions(stopEntry = true)
        )
        assertTrue(result.isSuccess)
        val entry = result.getOrThrow().orders.first { it.role == TouchTurnOrderRole.ENTRY }
        assertEquals("STP", entry.orderType)
    }

    @Test
    fun buildTouchTurnPlan_trailingDisabled_hasNoTrailOnStopLeg() {
        val result = WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = "AAPL",
            currencyCode = "USD",
            instrument = null,
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
            quantity = 10,
            options = WatchlistBracketOrderPlanner.BracketOrderOptions(adjustableTrailingStop = false)
        )
        assertTrue(result.isSuccess)
        val stop = result.getOrThrow().orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertNull(stop.trailTriggerPrice)
        assertNull(stop.trailArmStopPrice)
    }

    @Test
    fun buildTouchTurnPlan_rejectsInvalidGeometry() {
        val result = WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = "AAPL",
            currencyCode = "USD",
            instrument = null,
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 105.0,
            targetPrice = 110.0,
            quantity = 10
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun fromWatchlistPlan_usesPlanOrderOptions() {
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null,
            notes = null
        )
        val plan = WatchlistTradePlan(
            id = "plan-1",
            label = "Plan A",
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
            investmentAmount = 1_000.0,
            stopEntry = true,
            adjustableTrailingStop = false
        )
        val result = WatchlistBracketOrderPlanner.fromWatchlistPlan(entry, plan)
        assertTrue(result.isSuccess)
        val built = result.getOrThrow()
        assertEquals("STP", built.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)
        assertNull(built.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.trailTriggerPrice)
    }
}
