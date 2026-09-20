package daytrader.domain

import daytrader.broker.ExpectedRoundTripCommission
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeploymentPositionOutcomeCalculatorTest {
    private val deployment = StrategyDeployment(
        id = "d1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = DeploymentStatus.RUNNING,
        symbol = "TSLA",
        maxDollars = 1000,
        live = ActiveExecution(
            state = ExecutionState.FILLED,
            side = TradeSide.LONG,
            quantity = 10,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
        ),
    )

    private val longPosition = AccountPosition(
        account = "DU123",
        symbol = "TSLA",
        companyName = "Tesla",
        quantity = 10,
        avgPrice = 100.0,
        marketPrice = 105.0,
        priorClose = 98.0,
        totalUnrealizedPnL = 50.0,
        currency = "USD",
    )

    @Test
    fun resolve_fromBrokerOrders_longFixedStop() {
        val orders = listOf(
            stopOrder(stop = 95.0),
            takeProfitOrder(limit = 110.0),
        )
        val outcome = DeploymentPositionOutcomeCalculator.resolve(deployment, longPosition, orders)
        assertNotNull(outcome)
        // Gross TP 100 / stop -50, minus US RT commission 0.70 (LMT+LMT / LMT+STP)
        assertEquals(99.30, outcome.maxProfit, 0.001)
        assertEquals(-50.70, outcome.stopOutcome, 0.001)
        assertEquals(false, outcome.stopIsMinWin)
    }

    @Test
    fun resolve_trailingStopAboveEntry_isMinWin() {
        val orders = listOf(
            stopOrder(stop = 102.0, orderType = "TRAIL"),
            takeProfitOrder(limit = 110.0),
        )
        val outcome = DeploymentPositionOutcomeCalculator.resolve(deployment, longPosition, orders)
        assertNotNull(outcome)
        assertEquals(99.30, outcome.maxProfit, 0.001)
        assertEquals(19.30, outcome.stopOutcome, 0.001)
        assertEquals(true, outcome.stopIsMinWin)
    }

    @Test
    fun resolve_fallsBackToPlannedBracket() {
        val touchTurn = deployment.copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-14",
                status = TouchTurnCandleStatus.READY,
                plannedBracket = TouchTurnPlannedBracket(
                    side = TouchTurnTradeSide.LONG,
                    entry = 100.0,
                    stopLoss = 96.0,
                    takeProfit = 108.0,
                ),
            ),
        )
        val outcome = DeploymentPositionOutcomeCalculator.resolve(touchTurn, longPosition, emptyList())
        assertNotNull(outcome)
        assertEquals(79.30, outcome.maxProfit, 0.001)
        assertEquals(-40.70, outcome.stopOutcome, 0.001)
    }

    @Test
    fun resolve_fallsBackToLiveExecutionWithoutBrokerPosition() {
        val outcome = DeploymentPositionOutcomeCalculator.resolve(deployment, brokerPosition = null)
        assertNotNull(outcome)
        assertEquals(99.30, outcome.maxProfit, 0.001)
        assertEquals(-50.70, outcome.stopOutcome, 0.001)
    }

    @Test
    fun resolve_nullWhenFlat() {
        assertNull(
            DeploymentPositionOutcomeCalculator.resolve(
                deployment.copy(live = ActiveExecution.flat()),
                brokerPosition = null,
            )
        )
    }

    @Test
    fun resolve_prefersLiveStopOverPlannedBracket() {
        val touchTurn = deployment.copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-14",
                status = TouchTurnCandleStatus.READY,
                plannedBracket = TouchTurnPlannedBracket(
                    side = TouchTurnTradeSide.LONG,
                    entry = 100.0,
                    stopLoss = 90.0,
                    takeProfit = 120.0,
                ),
            ),
        )
        val orders = listOf(
            stopOrder(stop = 101.0, orderType = "TRAIL"),
            takeProfitOrder(limit = 110.0),
        )
        val outcome = DeploymentPositionOutcomeCalculator.resolve(touchTurn, longPosition, orders)
        assertNotNull(outcome)
        assertEquals(99.30, outcome.maxProfit, 0.001)
        assertEquals(9.30, outcome.stopOutcome, 0.001)
    }

    @Test
    fun resolve_hkSehk_subtractsRoundTripFeesFromOutcomes() {
        val hkDeployment = deployment.copy(
            symbol = "00939",
            instrument = InstrumentIdentity(
                symbol = "00939",
                currency = "HKD",
                exchange = "SEHK",
                primaryExch = "SEHK",
            ),
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 9_000,
                entryPrice = 8.56,
                stopPrice = 8.40,
                targetPrice = 8.80,
            ),
        )
        val hkPosition = AccountPosition(
            account = "DU123",
            symbol = "00939",
            companyName = "CCB",
            quantity = 9_000,
            avgPrice = 8.56,
            marketPrice = 8.60,
            priorClose = 8.50,
            totalUnrealizedPnL = 360.0,
            currency = "HKD",
        )
        val outcome = DeploymentPositionOutcomeCalculator.resolve(
            hkDeployment,
            hkPosition,
            emptyList(),
        )
        assertNotNull(outcome)
        val grossTp = (8.80 - 8.56) * 9_000
        val grossStop = (8.40 - 8.56) * 9_000
        assertTrue(outcome.maxProfit < grossTp)
        assertTrue(outcome.stopOutcome < grossStop)
        val tpCommission = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 9_000,
            entryPrice = 8.56,
            exitPrice = 8.80,
            currency = "HKD",
            primaryExch = "SEHK",
            exchange = "SEHK",
            entryOrderType = "LMT",
            exitOrderType = "LMT",
        )
        val stopCommission = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 9_000,
            entryPrice = 8.56,
            exitPrice = 8.40,
            currency = "HKD",
            primaryExch = "SEHK",
            exchange = "SEHK",
            entryOrderType = "LMT",
            exitOrderType = "STP",
        )
        assertEquals(grossTp - tpCommission, outcome.maxProfit, 0.02)
        assertEquals(grossStop - stopCommission, outcome.stopOutcome, 0.02)
    }

    private fun stopOrder(stop: Double, orderType: String = "STP") = WorkingOrder(
        orderId = 2,
        parentOrderId = 1,
        symbol = "TSLA",
        action = "SELL",
        quantity = 10,
        filled = 0,
        remaining = 10,
        orderType = orderType,
        limitPrice = null,
        stopPrice = stop,
        status = "Submitted",
        currency = "USD",
    )

    private fun takeProfitOrder(limit: Double) = WorkingOrder(
        orderId = 3,
        parentOrderId = 1,
        symbol = "TSLA",
        action = "SELL",
        quantity = 10,
        filled = 0,
        remaining = 10,
        orderType = "LMT",
        limitPrice = limit,
        stopPrice = null,
        status = "Submitted",
        currency = "USD",
    )
}
