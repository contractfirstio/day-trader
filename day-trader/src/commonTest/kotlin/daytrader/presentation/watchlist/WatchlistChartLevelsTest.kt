package daytrader.presentation.watchlist

import daytrader.domain.TradeSide
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistTradePlan
import daytrader.gateway.WorkingOrder
import daytrader.presentation.strategies.TouchTurnOrderLevelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchlistChartLevelsTest {

    @Test
    fun forEntry_usesPlacedPlanBracketPrices() {
        val entry = WatchlistEntry(
            id = "e1",
            symbol = "AAPL",
            companyName = "Apple",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            instrument = null,
            addedAtEpochMs = 1_700_000_000_000L,
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = "p1",
                    label = "Plan A",
                    side = TradeSide.LONG,
                    entryPrice = 100.0,
                    stopPrice = 95.0,
                    targetPrice = 110.0,
                    orderPlacedAtEpochMs = 1_700_000_000_000L,
                    placedOrderIds = listOf(1, 2, 3)
                )
            )
        )
        val levels = WatchlistChartLevels.forEntry(
            symbol = "AAPL",
            entry = entry,
            openOrders = emptyList()
        )
        assertEquals(4, levels.size)
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.ENTRY && it.price == 100.0 })
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.STOP_LOSS && it.price == 95.0 })
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.TAKE_PROFIT && it.price == 110.0 })
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.TRAIL_TRIGGER && it.price == 105.0 })
    }

    @Test
    fun forEntry_keepsPlannedEntryAfterEntryOrderLeavesBook() {
        val entry = WatchlistEntry(
            id = "e1",
            symbol = "AAPL",
            companyName = "Apple",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            instrument = null,
            addedAtEpochMs = 1_700_000_000_000L,
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = "p1",
                    label = "Plan A",
                    side = TradeSide.LONG,
                    entryPrice = 100.0,
                    stopPrice = 95.0,
                    targetPrice = 110.0,
                    orderPlacedAtEpochMs = 1_700_000_000_000L,
                    placedOrderIds = listOf(1, 2, 3)
                )
            )
        )
        val levels = WatchlistChartLevels.forEntry(
            symbol = "AAPL",
            entry = entry,
            openOrders = listOf(
                workingOrder(orderId = 2, symbol = "AAPL", limit = 110.0, parentId = 1),
                workingOrder(orderId = 3, symbol = "AAPL", stop = 95.0, parentId = 1)
            )
        )
        assertEquals(4, levels.size)
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.ENTRY && it.price == 100.0 })
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.TRAIL_TRIGGER && it.price == 105.0 })
    }

    @Test
    fun forEntry_mapsWorkingOrdersForSymbol() {
        val entry = WatchlistEntry(
            id = "e1",
            symbol = "AAPL",
            companyName = "Apple",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            instrument = null,
            addedAtEpochMs = 1_700_000_000_000L,
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = "p1",
                    label = "Plan A",
                    side = TradeSide.LONG,
                    entryPrice = 100.0,
                    stopPrice = 95.0,
                    targetPrice = 110.0,
                    orderPlacedAtEpochMs = 1_700_000_000_000L,
                    placedOrderIds = listOf(1, 2, 3)
                )
            )
        )
        val levels = WatchlistChartLevels.forEntry(
            symbol = "AAPL",
            entry = entry,
            openOrders = listOf(
                workingOrder(orderId = 1, symbol = "AAPL", limit = 100.0, parentId = 0),
                workingOrder(orderId = 2, symbol = "AAPL", limit = 110.0, parentId = 1),
                workingOrder(orderId = 3, symbol = "AAPL", stop = 95.0, parentId = 1)
            )
        )
        assertEquals(4, levels.size)
        assertTrue(levels.any { it.kind == TouchTurnOrderLevelKind.TRAIL_TRIGGER && it.price == 105.0 })
    }

    private fun workingOrder(
        orderId: Int,
        symbol: String,
        limit: Double? = null,
        stop: Double? = null,
        parentId: Int = 0
    ): WorkingOrder = WorkingOrder(
        orderId = orderId,
        symbol = symbol,
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
