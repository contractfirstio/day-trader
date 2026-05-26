package daytrader.presentation.strategies

import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnTradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnExecutedBracketLegsTest {

    @Test
    fun resolve_marksEntryAndTakeProfitFromFills() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 100.0, realized = null),
            trade(parentOrderId = 1, price = 110.0, realized = 50.0)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null)
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed)
        assertTrue(TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
        assertEquals(false, TouchTurnOrderLevelKind.STOP_LOSS in executed)
    }

    @Test
    fun resolve_marksStopLossWhenExitAtStop() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.SHORT,
            entry = 200.0,
            stopLoss = 205.0,
            takeProfit = 190.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 200.0, realized = null),
            trade(parentOrderId = 1, price = 205.0, realized = -25.0)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null)
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed)
    }

    @Test
    fun resolve_marksEntryEvenWhenFillPriceDiffersFromPlanned() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 100.25, realized = null)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null)
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed)
    }

    @Test
    fun resolve_classifiesWidenedTakeProfitByNearestLevel() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 100.0, realized = null),
            trade(parentOrderId = 1, price = 115.0, realized = 75.0)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null)
        assertTrue(TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
        assertEquals(false, TouchTurnOrderLevelKind.STOP_LOSS in executed)
    }

    @Test
    fun resolve_lossUsesStopLossEvenWhenExitPriceIsCloserToTakeProfitOnChart() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 100.0, realized = null),
            trade(parentOrderId = 1, price = 102.5, realized = -15.0)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null)
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed)
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed)
        assertEquals(false, TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
    }

    @Test
    fun resolve_winUsesFillPnlOverWrongNegativeSessionPnl() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 100.0, realized = null),
            trade(parentOrderId = 1, price = 110.0, realized = 50.0)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(
            trades = trades,
            plannedBracket = bracket,
            bracketSetup = null,
            sessionPnl = -99.0
        )
        assertTrue(TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
        assertEquals(false, TouchTurnOrderLevelKind.STOP_LOSS in executed)
    }

    @Test
    fun resolve_lossUsesSessionPnlEvenWhenExitPriceIsNearerTakeProfit() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, price = 100.0, realized = null),
            trade(parentOrderId = 1, price = 108.0, realized = -12.0)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(
            trades = trades,
            plannedBracket = bracket,
            bracketSetup = null,
            sessionPnl = -12.0
        )
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed)
        assertEquals(false, TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
    }

    @Test
    fun resolve_shortLossByPriceWhenExitPnlMissing() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.SHORT,
            entry = 200.0,
            stopLoss = 205.0,
            takeProfit = 190.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 10, price = 200.0, realized = null),
            trade(parentOrderId = 10, orderId = 11, price = 204.0, realized = null)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null, sessionPnl = null)
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed)
        assertEquals(false, TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
    }

    @Test
    fun resolve_longLossByPriceWhenRealizedPnLMissing() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 92.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 10, price = 100.0, realized = null),
            trade(parentOrderId = 10, orderId = 11, price = 93.0, realized = null)
        )
        val executed = TouchTurnExecutedBracketLegs.resolve(trades, bracket, null)
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed)
        assertEquals(false, TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
    }

    @Test
    fun resolve_emptyWhenNoTrades() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 1.0,
            stopLoss = 0.9,
            takeProfit = 1.1
        )
        assertEquals(emptySet(), TouchTurnExecutedBracketLegs.resolve(emptyList(), bracket, null))
    }

    private fun trade(
        parentOrderId: Int,
        orderId: Int = parentOrderId + 1,
        price: Double,
        realized: Double?
    ): SessionTrade = SessionTrade(
        execId = "e$orderId",
        orderId = orderId,
        permId = orderId + 100L,
        parentOrderId = parentOrderId,
        side = if (parentOrderId == 0) "BOT" else "SLD",
        quantity = 10,
        price = price,
        time = "2026-05-22T10:00:00",
        currency = "USD",
        realizedPnL = realized
    )
}
