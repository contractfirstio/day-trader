package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategySession
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnStatusBreadcrumbMapperTest {
    @Test
    fun nullSession_currentIsStartingSession() {
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(touchTurnSession = null),
            hasOpenPosition = false
        )
        assertEquals("Starting session", steps[0].label)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[0].state)
        assertEquals("Closing session", steps[6].label)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
    }

    @Test
    fun loadingSession_currentIsData() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.LOADING
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[0].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[1].state)
    }

    @Test
    fun failedSession_marksDataFailed() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.FAILED,
            errorMessage = "ADR error"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[0].state)
        assertEquals(TouchTurnBreadcrumbStepState.FAILED, steps[1].state)
    }

    @Test
    fun notLiquidity_skipsOrdersAndPosition_closingStillUpcoming() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 10.0,
            now = now
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
        assertTrue(steps.none { it.state == TouchTurnBreadcrumbStepState.CURRENT })
    }

    @Test
    fun completedSteps_showFormattedTimestamps() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.LOADING,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = "2026-05-22T09:30:05",
                dataReadyAt = "2026-05-22T09:30:12"
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false
        )
        assertEquals("09:30", steps[0].timestamp)
        assertEquals("09:30", steps[1].timestamp)
        assertEquals(null, steps[2].timestamp)
    }

    @Test
    fun openPosition_currentIsPosition() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).copy(ordersPlacedForSession = true)
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
    }

    private fun deployment(touchTurnSession: TouchTurnSessionContext?) = StrategyDeployment(
        id = "tt-1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = DeploymentStatus.RUNNING,
        symbol = "AAPL",
        maxDollars = 500,
        touchTurnSession = touchTurnSession
    )

    private fun readySession(
        candle: OhlcBar,
        rangeThreshold: Double,
        now: Long
    ): TouchTurnSessionContext {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            marketZoneId = "America/New_York",
            rangeThreshold = rangeThreshold,
            adr14 = rangeThreshold / 0.25
        )
        assertEquals(FirstCandleCloseStatus.CLOSED, session.candleCloseStatus(now))
        return session
    }

    private fun bar(time: String) = OhlcBar(
        open = 100.0,
        high = 101.0,
        low = 99.0,
        close = 100.5,
        time = time
    )

    private fun barEnd(time: String): Long =
        TouchTurnLogic.barEndEpochMillis(time, "America/New_York")!!

    @Test
    fun pipelineForLastClosedSession_usesMostRecentClosedRun() {
        val milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:00",
            dataReadyAt = "2026-05-22T09:30:10",
            closingSessionAt = "2026-05-22T11:00:00"
        )
        val instance = deployment(
            touchTurnSession = null
        ).copy(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(
                StrategySession(
                    id = "old",
                    date = "2026-05-21",
                    startedAt = "2026-05-21T09:30:00",
                    stoppedAt = "2026-05-21T10:00:00",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                    touchTurnMilestones = milestones.copy(dataReadyAt = "2026-05-21T09:31:00")
                ),
                StrategySession(
                    id = "latest",
                    date = "2026-05-22",
                    startedAt = "2026-05-22T09:30:00",
                    stoppedAt = "2026-05-22T11:00:00",
                    pnl = 1.0,
                    trades = 1,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                    touchTurnMilestones = milestones
                )
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.pipelineForLastClosedSession(instance)
        assertEquals(7, steps?.size)
        assertEquals("11:00", steps?.get(6)?.timestamp)
    }

    @Test
    fun stepsFromHistory_reconstructsCompletedPipeline() {
        val milestones = daytrader.domain.TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:05",
            dataReadyAt = "2026-05-22T09:30:12",
            barClosedAt = "2026-05-22T09:45:00",
            liquidityEvaluatedAt = "2026-05-22T09:45:01",
            ordersPlacedAt = "2026-05-22T09:45:05",
            positionOpenedAt = "2026-05-22T09:46:10",
            closingSessionAt = "2026-05-22T11:00:00"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.stepsFromHistory(
            milestones = milestones,
            startedAt = "2026-05-22T09:30:05",
            stoppedAt = "2026-05-22T11:00:01",
            hadLiquidityCandle = true,
            ordersPlacedForCandle = true,
            positionOpened = true
        )
        assertTrue(steps.all { it.state == TouchTurnBreadcrumbStepState.COMPLETED })
        assertEquals("09:30", steps[0].timestamp)
        assertEquals("11:00", steps[6].timestamp)
    }
}
