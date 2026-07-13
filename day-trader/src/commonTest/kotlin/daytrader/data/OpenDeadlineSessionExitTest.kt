package daytrader.data

import daytrader.gateway.AccountPosition
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.engine.support.FakeBrokerGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OpenDeadlineSessionExitTest {
    @Test
    fun execute_tightStopFillsImmediately_confirmsFlatAndCancelsAllOrders() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.setQuotes(mapOf("AAPL" to LiveQuote(symbol = "AAPL", bid = 149.0, ask = 149.05)))
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 500,
            marketFallbackConfirmTimeoutMs = 100,
            pollIntervalMs = 25
        )

        assertEquals(OpenDeadlineSessionExit.Result.PositionConfirmedFlat, result)
        assertTrue(gateway.tightenStopCalls.isNotEmpty())
        assertTrue(gateway.flattenedSymbols.isEmpty())
        assertTrue(gateway.closedPositions.isEmpty(), "must not market-close when tight stop fills")
        assertTrue(gateway.cancelCalls.any { it.preserveStopLoss })
        assertTrue(gateway.cancelCalls.last().preserveStopLoss == false)
        assertEquals(0, gateway.openOrders.value.size)
        assertTrue(gateway.positions.value.none { it.symbol == "AAPL" })
    }

    @Test
    fun execute_marketFallbackWhenTightStopDoesNotFill_neverFlattens() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.closeClearsPosition = true
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 100,
            marketFallbackConfirmTimeoutMs = 100,
            pollIntervalMs = 25
        )

        assertEquals(OpenDeadlineSessionExit.Result.PositionConfirmedFlatAfterMarketFallback, result)
        assertTrue(gateway.tightenStopCalls.isNotEmpty())
        assertTrue(gateway.flattenedSymbols.isEmpty())
        assertTrue(gateway.closedPositions.isNotEmpty())
        assertTrue(gateway.cancelCalls.any { it.preserveStopLoss })
        assertTrue(gateway.cancelCalls.last().preserveStopLoss == false)
        assertEquals(0, gateway.openOrders.value.size)
    }

    @Test
    fun execute_whenStopMissing_replacesNearMarketStop() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.setQuotes(mapOf("AAPL" to LiveQuote(symbol = "AAPL", bid = 149.0, ask = 149.05)))
        gateway.setOpenOrders(listOf(takeProfit("AAPL", 1002)))

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 100,
            marketFallbackConfirmTimeoutMs = 100,
            pollIntervalMs = 25
        )

        assertEquals(OpenDeadlineSessionExit.Result.PositionConfirmedFlat, result)
        assertTrue(gateway.tightenStopCalls.any { it.orderId == null })
        assertTrue(gateway.flattenedSymbols.isEmpty())
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
            marketFallbackConfirmTimeoutMs = 100,
            pollIntervalMs = 25
        ) as OpenDeadlineSessionExit.Result.CloseUnconfirmedStopLossRetained

        assertEquals(1, result.stopLossOrderCount)
        assertTrue(result.marketFallbackAttempted)
        assertTrue(
            gateway.openOrders.value.any { SessionOrderClassification.isProtectiveStopLoss(it) },
            "must retain or re-arm a protective stop when market close fails"
        )
        assertTrue(gateway.cancelCalls.all { it.preserveStopLoss })
        assertTrue(gateway.flattenedSymbols.isEmpty())
    }

    @Test
    fun execute_neverCancelsProtectiveStopWhilePositionStillOpen() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.closeClearsPosition = false
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))

        runBlocking {
            OpenDeadlineSessionExit.execute(
                gateway = gateway,
                symbol = "AAPL",
                knownPosition = shortPosition(),
                positions = gateway.positions,
                openOrders = gateway.openOrders,
                confirmTimeoutMs = 100,
                marketFallbackConfirmTimeoutMs = 100,
                pollIntervalMs = 25
            )
        }

        assertTrue(gateway.positions.value.any { it.symbol == "AAPL" })
        assertTrue(gateway.openOrders.value.any { SessionOrderClassification.isProtectiveStopLoss(it) })
        assertTrue(gateway.cancelCalls.none { !it.preserveStopLoss })
    }

    /**
     * COIN session-50b76edb44905955 (2026-07-13): single MKT fallback under batch pacing never
     * cleared the short; session retained STP@158.85. Must re-issue market close while still open.
     */
    @Test
    fun execute_marketFallback_retriesCloseWhenFirstAttemptDoesNotFlatten() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))
        gateway.closeClearsPosition = true
        gateway.closeClearsPositionAfterAttempts = 2
        gateway.removeProtectiveStopsOnCloseAttempt = true

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 50,
            marketFallbackConfirmTimeoutMs = 400,
            pollIntervalMs = 25,
            marketFallbackRetryIntervalMs = 80
        )

        assertEquals(
            OpenDeadlineSessionExit.Result.PositionConfirmedFlatAfterMarketFallback,
            result,
            "must retry market close until flat when the first attempt is ignored"
        )
        assertTrue(
            gateway.closedPositions.size >= 2,
            "expected repeated open_deadline_fallback market closes, got ${gateway.closedPositions.size}"
        )
        assertTrue(gateway.positions.value.none { it.symbol == "AAPL" })
    }

    /**
     * F session-3f4deedbb5fb0a07 (2026-07-13): concurrent OPEN_DEADLINE refresh wiped the position
     * cache while STP still worked; exit claimed flat and cancelled the stop with no market close /
     * exit fill. Must fall through to market fallback instead of cancelling protection.
     */
    @Test
    fun execute_refreshWipeWhileStopStillOpen_mustMarketCloseNotCancelStopEarly() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.setPositions(listOf(shortPosition()))
        gateway.setOpenOrders(listOf(stopLoss("AAPL", 1001), takeProfit("AAPL", 1002)))
        gateway.refreshPositionsClearsAllPositions = true
        gateway.closeClearsPosition = true

        val result = OpenDeadlineSessionExit.execute(
            gateway = gateway,
            symbol = "AAPL",
            knownPosition = shortPosition(),
            positions = gateway.positions,
            openOrders = gateway.openOrders,
            confirmTimeoutMs = 100,
            marketFallbackConfirmTimeoutMs = 100,
            pollIntervalMs = 25
        )

        assertEquals(
            OpenDeadlineSessionExit.Result.PositionConfirmedFlatAfterMarketFallback,
            result,
            "cache wipe with protective stop still open must not count as pre-market flat"
        )
        assertTrue(gateway.closedPositions.isNotEmpty(), "must market-close after rejecting false flat")
        val cancelWithoutPreserve = gateway.cancelCalls.filter { !it.preserveStopLoss }
        assertTrue(
            cancelWithoutPreserve.isNotEmpty(),
            "may cancel remaining orders only after market fallback confirms flat"
        )
        assertTrue(
            gateway.closedPositions.isNotEmpty() &&
                gateway.cancelCalls.indexOfFirst { !it.preserveStopLoss } >
                gateway.cancelCalls.indexOfFirst { it.preserveStopLoss },
            "TP cancel (preserve SL) must precede any cancel that drops the protective stop"
        )
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
