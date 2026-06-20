package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
class StrategySessionLogicTest {
    private val base = defaultStrategyDeployment(
        strategyType = StrategyType.QUICK_FLIP_SCALPER,
        symbol = "SPY",
        maxDollars = 500
    )

    @Test
    fun onSessionStarted_alwaysCreatesNewPerformanceRow() {
        val first = base.onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        val stopped = first.onSessionStopped(
            stoppedAt = "2026-05-22T10:00:00",
            snapshot = SessionStopSnapshot(positionOpened = false, sessionPnL = 0.0)
        )
        assertEquals(1, stopped.sessionHistory.count { it.status == SessionStatus.CLOSED })
        assertEquals("2026-05-22T10:00:00", stopped.sessionHistory.single().stoppedAt)
        assertEquals(false, stopped.sessionHistory.single().positionOpened)

        val second = stopped.onSessionStarted("2026-05-22", startedAt = "2026-05-22T11:00:00")
        assertEquals(1, second.sessionHistory.count { it.status == SessionStatus.IN_PROGRESS })
        assertEquals(2, second.sessionHistory.size)
        assertNotEquals(
            stopped.sessionHistory.first().id,
            second.inProgressSession()!!.id
        )
    }

    @Test
    fun withoutSessionHistoryEntry_removesMatchingRow() {
        val started = base.onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        val stopped = started.onSessionStopped(stoppedAt = "2026-05-22T10:00:00")
        val runId = stopped.sessionHistory.single().id
        val trimmed = stopped.withoutSessionHistoryEntry(runId)
        assertEquals(0, trimmed.sessionHistory.size)
    }

    @Test
    fun withoutClosedSessionHistory_keepsInProgressOnly() {
        val first = base.onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        val stopped = first.onSessionStopped(stoppedAt = "2026-05-22T10:00:00")
        val active = stopped.onSessionStarted("2026-05-22", startedAt = "2026-05-22T11:00:00")
        val trimmed = active.withoutClosedSessionHistory()
        assertEquals(1, trimmed.sessionHistory.size)
        assertEquals(SessionStatus.IN_PROGRESS, trimmed.sessionHistory.single().status)
    }

    @Test
    fun onSessionStopped_closesActiveRunAndClearsTouchTurnSession() {
        val touchTurn = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        )
        val started = touchTurn.onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
            .beginTouchTurnSession("2026-05-22")
        val activeId = started.inProgressSession()!!.id
        val stopped = started.onSessionStopped()
        assertNull(stopped.inProgressSession())
        assertNull(stopped.touchTurnSession)
        assertEquals(SessionStatus.CLOSED, stopped.sessionHistory.single { it.id == activeId }.status)
    }

    @Test
    fun updateInProgressSession_targetsActiveRunOnly() {
        val started = base.onSessionStarted("2026-05-22")
        val updated = started.updateInProgressSession { it.copy(trades = 3, pnl = 12.5) }
        assertEquals(3, updated.inProgressSession()?.trades)
        assertEquals(12.5, updated.inProgressSession()?.pnl)
    }

    @Test
    fun rollups_includes14dWindow() {
        val runs = listOf(
            StrategySession(
                id = "r1",
                date = "2026-05-20",
                pnl = 10.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED
            ),
            StrategySession(
                id = "r2",
                date = "2026-05-10",
                pnl = 5.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED
            )
        )
        val rollup = runs.rollups(asOfSessionDate = "2026-05-22")
        assertEquals(15.0, rollup.pnl14d)
        assertEquals(10.0, rollup.pnl7d)
        assertEquals(15.0, rollup.pnl30d)
    }

    @Test
    fun onSessionStopped_recordsStoppedAt_onInProgressRow() {
        val started = base.onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        assertEquals("", started.inProgressSession()!!.stoppedAt)
        val stopped = started.onSessionStopped(stoppedAt = "2026-05-22T11:15:30")
        val closed = stopped.sessionHistory.single()
        assertEquals("2026-05-22T09:30:00", closed.startedAt)
        assertEquals("2026-05-22T11:15:30", closed.stoppedAt)
    }

    @Test
    fun lastClosed_returnsMostRecentlyStoppedRun() {
        val runs = listOf(
            StrategySession(
                id = "r1",
                date = "2026-05-20",
                startedAt = "2026-05-20T09:30:00",
                stoppedAt = "2026-05-20T10:00:00",
                pnl = 10.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED
            ),
            StrategySession(
                id = "r2",
                date = "2026-05-22",
                startedAt = "2026-05-22T09:30:00",
                stoppedAt = "2026-05-22T11:00:00",
                pnl = -25.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED
            ),
            StrategySession(
                id = "r3",
                date = "2026-05-22",
                startedAt = "2026-05-22T12:00:00",
                pnl = 0.0,
                trades = 0,
                maxAtRisk = 500,
                status = SessionStatus.IN_PROGRESS
            ),
        )
        assertEquals("r2", runs.lastClosed()?.id)
        assertEquals(-25.0, runs.lastClosed()?.pnl)
    }

    @Test
    fun rollups_excludesNoTradeSessionsFromWinRate() {
        val runs = listOf(
            StrategySession(
                id = "r1",
                date = "2026-05-20",
                pnl = 10.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED,
                positionOpened = true,
            ),
            StrategySession(
                id = "r2",
                date = "2026-05-21",
                pnl = -5.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED,
                positionOpened = true,
            ),
            StrategySession(
                id = "r3",
                date = "2026-05-22",
                pnl = 0.0,
                trades = 0,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED,
                positionOpened = false,
            ),
        )
        val rollup = runs.rollups(asOfSessionDate = "2026-05-22")
        assertEquals(1, rollup.winDays)
        assertEquals(1, rollup.lossDays)
        assertEquals(1, rollup.noTradeDays)
        assertEquals(2, rollup.tradedDays)
        assertEquals(3, rollup.closedDays)
    }

    @Test
    fun hadPosition_falseWhenRulesBlockedTrade() {
        val session = StrategySession(
            id = "r1",
            date = "2026-05-22",
            pnl = 0.0,
            trades = 0,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            positionOpened = false,
        )
        assertEquals(false, session.hadPosition())
    }

    @Test
    fun hadPosition_trueWhenEntryFilled() {
        val session = StrategySession(
            id = "r1",
            date = "2026-05-22",
            pnl = 12.0,
            trades = 1,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            positionOpened = true,
        )
        assertEquals(true, session.hadPosition())
    }
}
