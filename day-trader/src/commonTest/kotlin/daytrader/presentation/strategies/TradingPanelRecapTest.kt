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

    @Test
    fun showsLiveMarketQuotes_whenRunning() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-1")
            .copy(status = DeploymentStatus.RUNNING)
        assertTrue(
            TradingPanelRecap.showsLiveMarketQuotes(deployment, dismissedRecapSessionIdByDeployment = emptyMap())
        )
    }

    @Test
    fun showsLiveMarketQuotes_whenRecapVisible() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-1")
        assertTrue(
            TradingPanelRecap.showsLiveMarketQuotes(deployment, dismissedRecapSessionIdByDeployment = emptyMap())
        )
    }

    @Test
    fun showsHistoricSession_evenWhenLatestDismissed() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-2").copy(
            sessionHistory = listOf(
                StrategySession(
                    id = "run-1",
                    date = "2026-05-27",
                    pnl = 1.0,
                    trades = 1,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                    stoppedAt = "2026-05-27T16:00:00Z",
                    touchTurnMilestones = TouchTurnMilestoneTimestamps(startingSessionAt = "2026-05-27T13:30:00Z"),
                ),
                StrategySession(
                    id = "run-2",
                    date = "2026-05-28",
                    pnl = 2.0,
                    trades = 1,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                    stoppedAt = "2026-05-28T16:00:00Z",
                    touchTurnMilestones = TouchTurnMilestoneTimestamps(startingSessionAt = "2026-05-28T13:30:00Z"),
                ),
            )
        )
        val dismissed = mapOf(deployment.id to "run-2")
        assertTrue(
            TradingPanelRecap.showsSessionRecap(
                deployment,
                dismissedRecapSessionIdByDeployment = dismissed,
                historicRunId = "run-1",
            )
        )
    }

    @Test
    fun hidesLiveMarketQuotes_whenRecapDismissed() {
        val deployment = stoppedTouchTurnWithHistory(sessionId = "run-1")
        assertFalse(
            TradingPanelRecap.showsLiveMarketQuotes(
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
