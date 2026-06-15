package daytrader.domain

import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertEquals(100.0, outcome.maxProfit, 0.001)
        assertEquals(-50.0, outcome.stopOutcome, 0.001)
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
        assertEquals(100.0, outcome.maxProfit, 0.001)
        assertEquals(20.0, outcome.stopOutcome, 0.001)
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
        assertEquals(80.0, outcome.maxProfit, 0.001)
        assertEquals(-40.0, outcome.stopOutcome, 0.001)
    }

    @Test
    fun resolve_fallsBackToLiveExecutionWithoutBrokerPosition() {
        val outcome = DeploymentPositionOutcomeCalculator.resolve(deployment, brokerPosition = null)
        assertNotNull(outcome)
        assertEquals(100.0, outcome.maxProfit, 0.001)
        assertEquals(-50.0, outcome.stopOutcome, 0.001)
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
        assertEquals(100.0, outcome.maxProfit, 0.001)
        assertEquals(10.0, outcome.stopOutcome, 0.001)
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
