package daytrader.presentation.strategies

import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TouchTurnSessionReasonUiTest {

    @Test
    fun dataFailed_includesErrorMessage() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-01",
            status = TouchTurnCandleStatus.FAILED,
            errorMessage = "Need 15 15m bars for ATR(14), got 0"
        )
        val ui = TouchTurnSessionReasonUi.forDecisionOutcome(
            TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            session
        )
        assertContains(ui.detail!!, "Need 15 15m bars")
    }

    @Test
    fun dataFailed_afterBarClosed_usesLiquidityRefetchCopy() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-03",
            status = TouchTurnCandleStatus.FAILED,
            errorMessage = "Closed 15-minute bar not final after 8 refetches",
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            milestones = TouchTurnMilestoneTimestamps(
                dataReadyAt = "2026-06-03T09:30:12",
                barClosedAt = "2026-06-03T09:45:00",
                dataFailedAt = "2026-06-03T09:46:00"
            )
        )
        val ui = TouchTurnSessionReasonUi.forDecisionOutcome(
            TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            session
        )
        assertContains(ui.headline, "closed bar")
    }

    @Test
    fun liveStatus_orphanBrokerOrders_warnsWhenNotPlacedForSession() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-03",
            status = TouchTurnCandleStatus.READY,
            openingBarTime = "20260603  09:30:00"
        )
        val ui = TouchTurnSessionReasonUi.liveStatus(
            session = session,
            hasOpenPosition = false,
            hasOpenOrders = true,
            closing = false,
            nowEpochMillis = System.currentTimeMillis()
        )
        assertNotNull(ui)
        assertContains(ui!!.headline, "not from this session")
    }

    @Test
    fun liveStatus_notLiquid_beforeOrders() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-01",
            status = TouchTurnCandleStatus.READY,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        )
        val ui = TouchTurnSessionReasonUi.liveStatus(
            session = session,
            hasOpenPosition = false,
            hasOpenOrders = false,
            closing = false,
            nowEpochMillis = System.currentTimeMillis()
        )
        assertNotNull(ui)
        assertContains(ui!!.headline, "not liquid")
    }

    @Test
    fun stopTrigger_noTrade_usesDecisionHeadline() {
        val ui = TouchTurnSessionReasonUi.forStopTrigger(
            trigger = TouchTurnSessionStopTrigger.NO_TRADE_DECISION,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION
        )
        assertEquals(
            TouchTurnSessionReasonUi.forDecisionOutcome(
                TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION
            ).headline,
            ui.headline
        )
    }
}
