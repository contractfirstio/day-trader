package daytrader.engine

import daytrader.data.DeploymentSessionStopEvaluator
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentSessionStopWatcherTest {
    @Test
    fun evaluate_noTradeDecisionStop() {
        val instance = runningTouchTurn().copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
            )
        )
        val candidates = DeploymentSessionStopEvaluator.evaluate(
            deployments = listOf(instance),
            positions = emptyList(),
            openOrders = emptyList(),
            fills = emptyList()
        )
        assertEquals(1, candidates.size)
        assertEquals(TouchTurnSessionStopTrigger.NO_TRADE_DECISION, candidates.first().trigger)
    }

    @Test
    fun evaluate_openDeadlineStop() {
        val instance = runningTouchTurn()
        val open = TouchTurnSessionStopLogic.sessionOpenEpochMillis(instance, "2026-05-22")!!
        val candidates = DeploymentSessionStopEvaluator.evaluate(
            deployments = listOf(instance),
            positions = emptyList(),
            openOrders = emptyList(),
            fills = emptyList(),
            nowEpochMillis = open + 90 * 60_000L
        )
        assertTrue(candidates.any { it.trigger == TouchTurnSessionStopTrigger.OPEN_DEADLINE })
    }

    private fun runningTouchTurn() = defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "AAPL",
        maxDollars = 500,
        status = DeploymentStatus.RUNNING
    ).onSessionStarted("2026-05-22").copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY
        )
    )
}
