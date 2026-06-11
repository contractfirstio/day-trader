package daytrader.presentation.watchlist

import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistTradePlan
import daytrader.gateway.BrokerFill
import daytrader.presentation.strategies.TouchTurnOrderLevelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchlistChartExecutionTest {

    @Test
    fun executedLevels_marksFilledEntryAndTakeProfit() {
        val entry = entryWithPlacedPlan(
            placedAt = 1_700_000_000_000L,
            orderIds = listOf(1, 2, 3)
        )
        val fills = listOf(
            fill(orderId = 1, parentOrderId = 0, price = 100.0, time = "2026-05-22T10:00:00"),
            fill(orderId = 2, parentOrderId = 1, price = 110.0, time = "2026-05-22T11:00:00", realizedPnL = 50.0)
        )
        val executed = WatchlistChartExecution.executedLevels(
            symbol = "AAPL",
            entry = entry,
            fills = fills
        )
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed)
        assertTrue(TouchTurnOrderLevelKind.TAKE_PROFIT in executed)
    }

    @Test
    fun executedLevels_usesPersistedLegsWhenFillsAreGone() {
        val entry = entryWithPlacedPlan(
            placedAt = 1_700_000_000_000L,
            orderIds = listOf(1, 2, 3),
            executedBracketLegs = listOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.STOP_LOSS)
        )
        val executed = WatchlistChartExecution.executedLevels(
            symbol = "AAPL",
            entry = entry,
            fills = emptyList()
        )
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed)
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed)
    }

    @Test
    fun mergeDetectedExecutedLegs_accumulatesRoles() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            executedBracketLegs = listOf(TouchTurnOrderRole.ENTRY)
        )
        val merged = WatchlistChartExecution.mergeDetectedExecutedLegs(
            plan = plan,
            detected = setOf(TouchTurnOrderLevelKind.TAKE_PROFIT)
        )
        assertEquals(
            setOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.TAKE_PROFIT),
            merged.toSet()
        )
    }

    private fun entryWithPlacedPlan(
        placedAt: Long,
        orderIds: List<Int>,
        executedBracketLegs: List<TouchTurnOrderRole> = emptyList()
    ) = WatchlistEntry(
        id = "e1",
        symbol = "AAPL",
        companyName = "Apple",
        marketZoneId = "America/New_York",
        currencyCode = "USD",
        instrument = null,
        addedAtEpochMs = placedAt,
        tradePlans = listOf(
            WatchlistTradePlan(
                id = "p1",
                label = "Plan A",
                side = TradeSide.LONG,
                entryPrice = 100.0,
                stopPrice = 95.0,
                targetPrice = 110.0,
                orderPlacedAtEpochMs = placedAt,
                placedOrderIds = orderIds,
                executedBracketLegs = executedBracketLegs
            )
        )
    )

    private fun fill(
        orderId: Int,
        parentOrderId: Int,
        price: Double,
        time: String,
        realizedPnL: Double? = null
    ) = BrokerFill(
        execId = "exec-$orderId",
        orderId = orderId,
        permId = orderId.toLong(),
        parentOrderId = parentOrderId,
        symbol = "AAPL",
        side = if (parentOrderId == 0) "BUY" else "SELL",
        quantity = 10,
        price = price,
        time = time,
        realizedPnL = realizedPnL
    )
}
