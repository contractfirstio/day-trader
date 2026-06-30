package daytrader.presentation.strategies

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderSizingResult
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnTradeSide
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
    fun liveStatus_entryFilledWhileClosing_doesNotSayClosedWithoutFill() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-03",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            milestones = TouchTurnMilestoneTimestamps(
                ordersPlacedAt = "2026-06-03T09:45:03",
                positionOpenedAt = "2026-06-03T09:46:00",
                closingSessionAt = "2026-06-03T09:47:00"
            )
        )
        val ui = TouchTurnSessionReasonUi.liveStatus(
            session = session,
            hasOpenPosition = false,
            hasOpenOrders = false,
            closing = true,
            nowEpochMillis = System.currentTimeMillis(),
            deploymentRunning = true
        )
        assertNotNull(ui)
        assertContains(ui!!.headline, "Entry filled")
    }

    @Test
    fun liveStatus_openDeadlineWhileRunning_usesStoppingCopy() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-03",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            milestones = TouchTurnMilestoneTimestamps(ordersPlacedAt = "2026-06-03T09:45:03")
        )
        val ui = TouchTurnSessionReasonUi.liveStatus(
            session = session,
            hasOpenPosition = false,
            hasOpenOrders = false,
            closing = true,
            nowEpochMillis = System.currentTimeMillis(),
            deploymentRunning = true
        )
        assertNotNull(ui)
        assertContains(ui!!.headline, "open deadline")
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
    fun insufficientMaxDollars_usesPersistedDetailMessage() {
        val detail = TouchTurnOrderPlanner.insufficientFundsDetailMessage(
            maxDollars = 50_000,
            currencyCode = "HKD",
            entryPrice = 211.4,
            sizing = TouchTurnOrderSizingResult.BelowMinimum(
                rawQuantity = 236,
                minimumLot = 1_000,
                minimumNotional = 211_400.0,
            ),
        )
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-30",
            status = TouchTurnCandleStatus.READY,
            currencyCode = "HKD",
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
            decisionDetailMessage = detail,
        )
        val ui = TouchTurnSessionReasonUi.forDecisionOutcome(
            TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
            session
        )
        assertContains(ui.headline, "min lot")
        assertEquals(detail, ui.detail)
    }

    @Test
    fun liveStatus_openPositionWithoutOrders_warnsNoProtectiveOrders() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-10",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            milestones = TouchTurnMilestoneTimestamps(
                ordersPlacedAt = "2026-06-10T15:15:11",
                positionOpenedAt = "2026-06-10T15:15:30"
            )
        )
        val ui = TouchTurnSessionReasonUi.liveStatus(
            session = session,
            hasOpenPosition = true,
            hasOpenOrders = false,
            closing = false,
            nowEpochMillis = System.currentTimeMillis()
        )
        assertNotNull(ui)
        assertContains(ui!!.headline, "no protective orders")
    }

    @Test
    fun forTrailingStopInvalid_returnsWarningWhenConfigInvalid() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-12",
            status = TouchTurnCandleStatus.READY,
            setup = TouchTurnBracketSetup(
                range = 9.2,
                rangeThreshold = 3.0,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.LONG,
                entry = 382.724,
                stopLoss = 382.034,
                takeProfit = 388.244
            ),
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                trailingStopTriggerFractionOfEntryToTp = 1.5
            )
        )
        val ui = TouchTurnSessionReasonUi.forTrailingStopInvalid(session)
        assertNotNull(ui)
        assertEquals("Trailing stop not applied", ui.headline)
        assertContains(ui.detail!!, "fixed stop only")
    }
}
