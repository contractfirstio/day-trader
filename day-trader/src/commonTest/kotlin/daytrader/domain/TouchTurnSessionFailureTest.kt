package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnSessionFailureTest {
    @Test
    fun withTouchTurnCandleFailed_preservesBarClosedMilestone() {
        val deployment = StrategyDeployment(
            id = "d1",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = DeploymentStatus.RUNNING,
            symbol = "0700",
            maxDollars = 500,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-03",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = "20260603  09:30:00",
                marketZoneId = "Asia/Hong_Kong",
                rangeThreshold = 1.0,
                milestones = TouchTurnMilestoneTimestamps(
                    startingSessionAt = "2026-06-03T09:30:00",
                    dataReadyAt = "2026-06-03T09:30:12",
                    barClosedAt = "2026-06-03T09:45:00"
                )
            )
        )
        val failed = deployment.withTouchTurnCandleFailed(
            sessionDate = "2026-06-03",
            message = "Closed 15-minute bar not final after 8 refetches"
        )
        val session = failed.touchTurnSession!!
        assertEquals(TouchTurnCandleStatus.FAILED, session.status)
        assertEquals("2026-06-03T09:45:00", session.milestones.barClosedAt)
        assertEquals("2026-06-03T09:30:12", session.milestones.dataReadyAt)
        assertTrue(session.milestones.dataFailedAt != null)
        assertTrue(session.failedDuringLiquidityRefetch())
    }

    @Test
    fun failedDuringLiquidityRefetch_falseWhenBarNeverClosed() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-03",
            status = TouchTurnCandleStatus.FAILED,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            milestones = TouchTurnMilestoneTimestamps(
                dataReadyAt = "2026-06-03T09:30:12",
                dataFailedAt = "2026-06-03T09:31:00"
            )
        )
        assertEquals(false, session.failedDuringLiquidityRefetch())
    }
}
