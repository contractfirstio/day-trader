package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
class StrategyRunLogicTest {
    private val base = defaultStrategyInstance(
        strategyType = StrategyType.QUICK_FLIP_SCALPER,
        symbol = "SPY",
        maxDollars = 500
    )

    @Test
    fun onRunStarted_alwaysCreatesNewPerformanceRow() {
        val first = base.onRunStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        val stopped = first.onRunStopped(
            stoppedAt = "2026-05-22T10:00:00",
            snapshot = RunStopSnapshot(positionOpened = false, sessionPnL = 0.0)
        )
        assertEquals(1, stopped.performance.count { it.status == RunStatus.CLOSED })
        assertEquals("2026-05-22T10:00:00", stopped.performance.single().stoppedAt)
        assertEquals(false, stopped.performance.single().positionOpened)

        val second = stopped.onRunStarted("2026-05-22", startedAt = "2026-05-22T11:00:00")
        assertEquals(1, second.performance.count { it.status == RunStatus.IN_PROGRESS })
        assertEquals(2, second.performance.size)
        assertNotEquals(
            stopped.performance.first().id,
            second.inProgressRun()!!.id
        )
    }

    @Test
    fun withoutPerformanceRun_removesMatchingRow() {
        val started = base.onRunStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        val stopped = started.onRunStopped(stoppedAt = "2026-05-22T10:00:00")
        val runId = stopped.performance.single().id
        val trimmed = stopped.withoutPerformanceRun(runId)
        assertEquals(0, trimmed.performance.size)
    }

    @Test
    fun onRunStopped_closesActiveRunAndClearsTouchTurnSession() {
        val touchTurn = defaultStrategyInstance(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        )
        val started = touchTurn.onRunStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
            .beginTouchTurnSession("2026-05-22")
        val activeId = started.inProgressRun()!!.id
        val stopped = started.onRunStopped()
        assertNull(stopped.inProgressRun())
        assertNull(stopped.touchTurnSession)
        assertEquals(RunStatus.CLOSED, stopped.performance.single { it.id == activeId }.status)
    }

    @Test
    fun updateInProgressRun_targetsActiveRunOnly() {
        val started = base.onRunStarted("2026-05-22")
        val updated = started.updateInProgressRun { it.copy(trades = 3, pnl = 12.5) }
        assertEquals(3, updated.inProgressRun()?.trades)
        assertEquals(12.5, updated.inProgressRun()?.pnl)
    }

    @Test
    fun rollups_includes14dWindow() {
        val runs = listOf(
            StrategyRun(
                id = "r1",
                date = "2026-05-20",
                pnl = 10.0,
                trades = 1,
                maxAtRisk = 500,
                status = RunStatus.CLOSED
            ),
            StrategyRun(
                id = "r2",
                date = "2026-05-10",
                pnl = 5.0,
                trades = 1,
                maxAtRisk = 500,
                status = RunStatus.CLOSED
            )
        )
        val rollup = runs.rollups(asOfSessionDate = "2026-05-22")
        assertEquals(15.0, rollup.pnl14d)
        assertEquals(10.0, rollup.pnl7d)
        assertEquals(15.0, rollup.pnl30d)
    }

    @Test
    fun onRunStopped_recordsStoppedAt_onInProgressRow() {
        val started = base.onRunStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        assertEquals("", started.inProgressRun()!!.stoppedAt)
        val stopped = started.onRunStopped(stoppedAt = "2026-05-22T11:15:30")
        val closed = stopped.performance.single()
        assertEquals("2026-05-22T09:30:00", closed.startedAt)
        assertEquals("2026-05-22T11:15:30", closed.stoppedAt)
    }
}
