package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(TouchTurnOrderRole.TAKE_PROFIT, plan.orders[1].role)
        assertEquals(TouchTurnOrderRole.STOP_LOSS, plan.orders[2].role)
        assertEquals("STP", plan.orders[2].orderType)
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
}
