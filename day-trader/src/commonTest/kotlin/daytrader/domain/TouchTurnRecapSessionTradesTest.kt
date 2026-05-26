package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnRecapSessionTradesTest {

    @Test
    fun recapSessionTrades_prefersClosedRunWithFillsOverMilestoneOnlyRun() {
        val withFills = StrategySession(
            id = "older",
            date = "2026-05-21",
            stoppedAt = "2026-05-21T10:00:00",
            pnl = 10.0,
            trades = 2,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            touchTurnMilestones = TouchTurnMilestoneTimestamps(),
            sessionTrades = listOf(
                SessionTrade(
                    execId = "e1",
                    orderId = 1,
                    permId = 1L,
                    parentOrderId = 0,
                    side = "BOT",
                    quantity = 1,
                    price = 100.0,
                    time = "2026-05-21T10:00:00"
                )
            )
        )
        val milestonesOnly = StrategySession(
            id = "newer",
            date = "2026-05-22",
            stoppedAt = "2026-05-22T10:00:00",
            pnl = 0.0,
            trades = 0,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            touchTurnMilestones = TouchTurnMilestoneTimestamps(),
            sessionTrades = emptyList()
        )
        val deployment = StrategyDeployment(
            id = "d1",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = DeploymentStatus.STOPPED,
            symbol = "SPY",
            maxDollars = 500,
            sessionHistory = listOf(milestonesOnly, withFills)
        )
        assertEquals(1, deployment.touchTurnRecapSessionTrades().size)
        assertEquals("e1", deployment.touchTurnRecapSessionTrades().single().execId)
    }

    @Test
    fun recapSessionTrades_usesInProgressWhenRunning() {
        val deployment = StrategyDeployment(
            id = "d1",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = DeploymentStatus.RUNNING,
            symbol = "SPY",
            maxDollars = 500,
            sessionHistory = listOf(
                StrategySession(
                    id = "run",
                    date = "2026-05-22",
                    startedAt = "2026-05-22T10:00:00",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 500,
                    status = SessionStatus.IN_PROGRESS,
                    sessionTrades = listOf(
                        SessionTrade(
                            execId = "live",
                            orderId = 1,
                            permId = 1L,
                            parentOrderId = 0,
                            side = "BOT",
                            quantity = 1,
                            price = 50.0,
                            time = "2026-05-22T10:00:00"
                        )
                    )
                )
            )
        )
        assertTrue(deployment.touchTurnRecapSessionTrades().any { it.execId == "live" })
    }
}
