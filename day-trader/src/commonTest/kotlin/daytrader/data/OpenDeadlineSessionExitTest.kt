package daytrader.data

import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import daytrader.engine.support.FakeBrokerGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OpenDeadlineSessionExitTest {
    @Test
    fun execute_confirmsFlatBeforeSessionCleanup_cancelsAllOrders() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.closeClearsPosition = true
        gateway.setOpenOrders(
            listOf(
                stopLoss("AAPL", 1001),
                takeProfit("AAPL", 1002)
            )
        )

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 2_000,
            pollIntervalMs = 25
        )

        assertEquals(OpenDeadlineSessionExit.Result.PositionConfirmedFlat, result)
        assertTrue(gateway.refreshPositionsInvocationCount >= 1)
        assertTrue(gateway.cancelCalls.any { it.preserveStopLoss })
        assertTrue(gateway.cancelCalls.last().preserveStopLoss == false)
        assertEquals(0, gateway.openOrders.value.size)
        assertTrue(gateway.closedPositions.any { it.symbol == "AAPL" })
    }

    @Test
    fun execute_whenBrokerDropsStopLoss_recoversViaFlattenAndConfirmsFlat() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.closeClearsPosition = true
        gateway.closeClearsPositionAfterAttempts = 2
        gateway.removeProtectiveStopsOnCloseAttempt = true
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 100,
            pollIntervalMs = 25
        )

        assertEquals(OpenDeadlineSessionExit.Result.PositionConfirmedFlatAfterRecovery, result)
        assertTrue(gateway.flattenedSymbols.contains("AAPL"))
        assertTrue(gateway.positions.value.none { it.symbol == "AAPL" })
        assertEquals(0, gateway.openOrders.value.size)
    }

    @Test
    fun execute_closeUnconfirmed_retainsStopLossOnly() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.closeClearsPosition = false
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 100,
            pollIntervalMs = 25
        ) as OpenDeadlineSessionExit.Result.CloseUnconfirmedStopLossRetained

        assertEquals(1, result.stopLossOrderCount)
        assertEquals(listOf(1001), gateway.openOrders.value.map { it.orderId })
        assertTrue(gateway.cancelCalls.any { it.preserveStopLoss })
        assertTrue(gateway.cancelCalls.none { !it.preserveStopLoss })
    }

    private fun shortPosition() = AccountPosition(
        account = "DU123",
        symbol = "AAPL",
        companyName = "Apple",
        quantity = -100,
        avgPrice = 150.0,
        marketPrice = 149.0,
        priorClose = 148.0,
        totalUnrealizedPnL = 100.0,
        currency = "USD"
    )

    private fun stopLoss(symbol: String, orderId: Int) = WorkingOrder(
        orderId = orderId,
        symbol = symbol,
        action = "BUY",
        quantity = 100,
        filled = 0,
        remaining = 100,
        orderType = "STP",
        limitPrice = null,
        stopPrice = 155.0,
        status = "Submitted",
        currency = "USD"
    )

    private fun takeProfit(symbol: String, orderId: Int) = WorkingOrder(
        orderId = orderId,
        symbol = symbol,
        action = "BUY",
        quantity = 100,
        filled = 0,
        remaining = 100,
        orderType = "LMT",
        limitPrice = 140.0,
        stopPrice = null,
        status = "Submitted",
        currency = "USD"
    )
}
