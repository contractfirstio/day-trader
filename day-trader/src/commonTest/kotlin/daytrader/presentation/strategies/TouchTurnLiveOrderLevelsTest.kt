package daytrader.presentation.strategies

import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnLiveOrderLevelsTest {

    @Test
    fun fromWorkingOrders_mapsBracketLegs() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val orders = listOf(
            workingOrder(orderId = 1, limit = 100.0, parentId = 0),
            workingOrder(orderId = 2, limit = 110.0, parentId = 1),
            workingOrder(orderId = 3, stop = 95.0, parentId = 1)
        )
        val levels = TouchTurnLiveOrderLevels.fromWorkingOrders(orders, bracket, null)
        assertEquals(3, levels.size)
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.ENTRY && it.price == 100.0 })
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.TAKE_PROFIT && it.price == 110.0 })
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.STOP_LOSS && it.price == 95.0 })
    }

    @Test
    fun fromWorkingOrders_omitsFilledOrders() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val levels = TouchTurnLiveOrderLevels.fromWorkingOrders(
            openOrders = listOf(workingOrder(orderId = 2, limit = 110.0, parentId = 1)),
            plannedBracket = bracket,
            bracketSetup = null
        )
        assertEquals(1, levels.size)
        assertEquals(TouchTurnOrderLevelKind.TAKE_PROFIT, levels.single().kind)
    }

    private fun workingOrder(
        orderId: Int,
        limit: Double? = null,
        stop: Double? = null,
        parentId: Int = 0
    ): WorkingOrder = WorkingOrder(
        orderId = orderId,
        symbol = "SPY",
        action = "BUY",
        quantity = 10,
        filled = 0,
        remaining = 10,
        orderType = if (stop != null) "STP" else "LMT",
        limitPrice = limit,
        stopPrice = stop,
        status = "Submitted",
        currency = "USD",
        parentOrderId = parentId
    )
}
