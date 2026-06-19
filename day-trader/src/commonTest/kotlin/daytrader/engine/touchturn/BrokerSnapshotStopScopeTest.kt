package daytrader.engine.touchturn

import daytrader.engine.BrokerSnapshotSource
import daytrader.engine.TouchTurnCommand
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerSnapshotStopScopeTest {
    @Test
    fun affectedSymbols_ignoresMarkOnlyPositionUpdates() {
        val position = samplePosition(quantity = 10, marketPrice = 100.0)
        val markOnly = position.copy(marketPrice = 101.0, totalUnrealizedPnL = 12.0)

        val symbols = BrokerSnapshotStopScope.affectedSymbols(
            previousPositions = listOf(position),
            previousOpenOrders = emptyList(),
            previousFills = emptyList(),
            positions = listOf(markOnly),
            openOrders = emptyList(),
            fills = emptyList()
        )

        assertTrue(symbols.isEmpty())
    }

    @Test
    fun affectedSymbols_detectsPositionQuantityChange() {
        val previous = samplePosition(quantity = 10)
        val current = samplePosition(quantity = 0)

        val symbols = BrokerSnapshotStopScope.affectedSymbols(
            previousPositions = listOf(previous),
            previousOpenOrders = emptyList(),
            previousFills = emptyList(),
            positions = listOf(current),
            openOrders = emptyList(),
            fills = emptyList()
        )

        assertEquals(setOf("AAPL"), symbols)
    }

    @Test
    fun affectedSymbols_detectsNewFills() {
        val fill = BrokerFill(
            execId = "fill-1",
            orderId = 1,
            permId = 1L,
            parentOrderId = 0,
            symbol = "MSFT",
            side = "BOT",
            quantity = 1,
            price = 100.0,
            time = "2026-06-19T10:00:00Z"
        )

        val symbols = BrokerSnapshotStopScope.affectedSymbols(
            previousPositions = emptyList(),
            previousOpenOrders = emptyList(),
            previousFills = emptyList(),
            positions = emptyList(),
            openOrders = emptyList(),
            fills = listOf(fill)
        )

        assertEquals(setOf("MSFT"), symbols)
    }

    @Test
    fun affectedSymbols_detectsOpenOrderChanges() {
        val previous = sampleOrder(orderId = 1, status = "Submitted", remaining = 10)
        val current = sampleOrder(orderId = 1, status = "Filled", remaining = 0)

        val symbols = BrokerSnapshotStopScope.affectedSymbols(
            previousPositions = emptyList(),
            previousOpenOrders = listOf(previous),
            previousFills = emptyList(),
            positions = emptyList(),
            openOrders = listOf(current),
            fills = emptyList()
        )

        assertEquals(setOf("AAPL"), symbols)
    }

    @Test
    fun brokerSnapshotMerger_openOrdersSource_doesNotOverwriteFills() {
        val entryFill = sampleFill(execId = "entry", parentOrderId = 0)
        val exitFill = sampleFill(execId = "exit", parentOrderId = 1003)
        val command = TouchTurnCommand.BrokerSnapshot(
            source = BrokerSnapshotSource.OPEN_ORDERS,
            positions = listOf(samplePosition(quantity = -10)),
            openOrders = emptyList(),
            fills = listOf(entryFill)
        )

        val (_, _, fills) = BrokerSnapshotMerger.apply(
            source = BrokerSnapshotSource.OPEN_ORDERS,
            command = command,
            currentPositions = emptyList(),
            currentOpenOrders = listOf(sampleOrder(orderId = 1004, status = "Submitted", remaining = 1)),
            currentFills = listOf(entryFill, exitFill)
        )

        assertEquals(listOf(entryFill, exitFill), fills)
    }

    @Test
    fun brokerSnapshotMerger_fillsSource_rejectsRegressiveSnapshot() {
        val entryFill = sampleFill(execId = "entry", parentOrderId = 0)
        val exitFill = sampleFill(execId = "exit", parentOrderId = 1003)
        val command = TouchTurnCommand.BrokerSnapshot(
            source = BrokerSnapshotSource.FILLS,
            positions = emptyList(),
            openOrders = emptyList(),
            fills = listOf(entryFill)
        )

        val (_, _, fills) = BrokerSnapshotMerger.apply(
            source = BrokerSnapshotSource.FILLS,
            command = command,
            currentPositions = emptyList(),
            currentOpenOrders = emptyList(),
            currentFills = listOf(entryFill, exitFill)
        )

        assertEquals(listOf(entryFill, exitFill), fills)
    }

    private fun sampleFill(execId: String, parentOrderId: Int) = BrokerFill(
        execId = execId,
        orderId = if (parentOrderId == 0) 1003 else 1004,
        permId = 1L,
        parentOrderId = parentOrderId,
        symbol = "AAPL",
        side = if (parentOrderId == 0) "SLD" else "BOT",
        quantity = 10,
        price = 100.0,
        time = "2026-06-19T10:05:00Z"
    )

    private fun samplePosition(quantity: Int, marketPrice: Double = 100.0) = AccountPosition(
        account = "DU123",
        symbol = "AAPL",
        companyName = "Apple",
        quantity = quantity,
        avgPrice = 100.0,
        marketPrice = marketPrice,
        priorClose = 99.0,
        totalUnrealizedPnL = 0.0,
        currency = "USD"
    )

    private fun sampleOrder(orderId: Int, status: String, remaining: Int) = WorkingOrder(
        orderId = orderId,
        symbol = "AAPL",
        action = "BUY",
        quantity = 10,
        filled = 10 - remaining,
        remaining = remaining,
        orderType = "LMT",
        limitPrice = 100.0,
        stopPrice = null,
        status = status,
        currency = "USD"
    )
}
