package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun sessionTrade(parentOrderId: Int, execId: String = "e-$parentOrderId") = SessionTrade(
    execId = execId,
    orderId = parentOrderId + 1,
    permId = 1L,
    parentOrderId = parentOrderId,
    side = "BUY",
    quantity = 1,
    price = 100.0,
    time = "2026-05-25T10:00:00",
    realizedPnL = if (parentOrderId != 0) 5.0 else null
)

class DeploymentSessionStopLogicTest {
    @Test
    fun shouldStopAfterTradeOutcome_whenFlatWithEntryAndExitFills() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        val trades = listOf(sessionTrade(parentOrderId = 0), sessionTrade(parentOrderId = 1, execId = "e-exit"))
        assertTrue(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(
                    touchTurnSession = touchTurnSessionWithOrdersPlaced()
                ),
                sessionTrades = trades,
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun shouldStopAfterTradeOutcome_falseWhilePositionOrOrdersOpen() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        val trades = listOf(sessionTrade(parentOrderId = 0), sessionTrade(parentOrderId = 1, execId = "e-exit"))
        val session = touchTurnSessionWithOrdersPlaced()
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = session),
                sessionTrades = trades,
                hasOpenPosition = true,
                hasOpenOrders = false
            )
        )
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = session),
                sessionTrades = trades,
                hasOpenPosition = false,
                hasOpenOrders = true
            )
        )
    }

    @Test
    fun shouldStopAfterTradeOutcome_falseWithOnlyEntryFill() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = touchTurnSessionWithOrdersPlaced()),
                sessionTrades = listOf(sessionTrade(parentOrderId = 0)),
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }
}

private fun touchTurnSessionWithOrdersPlaced(): TouchTurnSessionContext =
    TouchTurnSessionContext(
        sessionDate = "2026-05-25",
        status = TouchTurnCandleStatus.READY,
        ordersPlacedForSession = true
    )
