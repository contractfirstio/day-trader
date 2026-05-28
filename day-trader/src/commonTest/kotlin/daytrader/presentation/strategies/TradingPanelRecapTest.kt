package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.defaultStrategyDeployment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingPanelRecapTest {
    @Test
    fun showsLastSession_whenStoppedWithClosedRunAndNotDismissed() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-1")
        assertTrue(
            TradingPanelRecap.showsLastSession(deployment, dismissedRecapSessionIdByDeployment = emptyMap())
        )
    }

    @Test
    fun hidesLastSession_whenDismissedForThatRun() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-1")
        assertFalse(
            TradingPanelRecap.showsLastSession(
                deployment,
                dismissedRecapSessionIdByDeployment = mapOf(deployment.id to "run-1"),
            )
        )
    }

    @Test
    fun showsLastSession_againAfterNewClosedRun() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-2")
        assertTrue(
            TradingPanelRecap.showsLastSession(
                deployment,
                dismissedRecapSessionIdByDeployment = mapOf(deployment.id to "run-1"),
            )
        )
    }

    private fun stoppedTouchTurnWithHistory(sessionId: String): StrategyDeployment {
        val session = StrategySession(
            id = sessionId,
            date = "2026-05-28",
            pnl = 0.0,
            trades = 0,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            stoppedAt = "2026-05-28T16:00:00Z",
            touchTurnMilestones = TouchTurnMilestoneTimestamps(startingSessionAt = "2026-05-28T13:30:00Z"),
        )
        return defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
        ).copy(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(session),
        )
    }
}
