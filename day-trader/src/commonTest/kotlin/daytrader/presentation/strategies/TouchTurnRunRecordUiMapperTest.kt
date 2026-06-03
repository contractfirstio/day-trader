package daytrader.presentation.strategies

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnRunMarketInputs
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSessionDecision
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnStopEvent
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnRunRecordUiMapperTest {
    @Test
    fun from_buildsCompactTeaserAndBody() {
        val ui = TouchTurnRunRecordUiMapper.from(
            TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 500,
                    startedBy = TouchTurnSessionStartedBy.AUTO_MARKET_OPEN,
                    brokerId = BrokerId.EMULATOR
                ),
                marketInputs = TouchTurnRunMarketInputs(
                    openingBar = OhlcBar(100.0, 110.0, 99.0, 108.0, "20260522  09:30:00"),
                    adr14 = 40.0,
                    currencyCode = "HKD",
                    marketZoneId = "Asia/Hong_Kong"
                ),
                decision = TouchTurnSessionDecision(
                    outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                    plannedQuantity = 4,
                    plannedBracket = TouchTurnPlannedBracket(
                        side = TouchTurnTradeSide.SHORT,
                        entry = 110.0,
                        stopLoss = 113.0,
                        takeProfit = 103.0
                    )
                ),
                stopEvent = TouchTurnStopEvent(
                    stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                    brokerUnrealizedPnLAtStop = 12.5
                ),
                milestones = TouchTurnMilestoneTimestamps()
            )
        )

        assertEquals("Bracket orders submitted · Session stopped — trade cycle complete", ui.teaser)
        assertTrue(ui.body.contains("Auto"))
        assertTrue(ui.body.contains("Emu"))
        assertTrue(ui.body.contains("ADR"))
        assertTrue(ui.body.contains("Bar"))
        assertTrue(ui.body.contains("S×4"))
        assertTrue(ui.body.contains("PnL@stop"))
    }

    @Test
    fun from_showsPaperLiveLabelWhenBrokerKindSet() {
        val ui = TouchTurnRunRecordUiMapper.from(
            TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 500,
                    startedBy = TouchTurnSessionStartedBy.MANUAL,
                    brokerId = BrokerId.EMULATOR,
                    brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA
                ),
                marketInputs = TouchTurnRunMarketInputs(),
                decision = TouchTurnSessionDecision(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY),
                stopEvent = TouchTurnStopEvent(TouchTurnSessionStopTrigger.NO_TRADE_DECISION),
                milestones = TouchTurnMilestoneTimestamps()
            )
        )
        assertTrue(ui.body.contains("Paper·IB"))
    }

    @Test
    fun effectiveStopTrigger_recoversFromManualWhenClosingMilestoneSet() {
        val record = TouchTurnRunRecord(
            runContext = TouchTurnRunContext(500, TouchTurnSessionStartedBy.MANUAL, BrokerId.EMULATOR),
            marketInputs = TouchTurnRunMarketInputs(),
            decision = TouchTurnSessionDecision(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED),
            stopEvent = TouchTurnStopEvent(TouchTurnSessionStopTrigger.MANUAL),
            milestones = TouchTurnMilestoneTimestamps(closingSessionAt = "2026-05-25T11:00:00")
        )
        assertEquals(
            TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
            TouchTurnRunRecordUiMapper.effectiveStopTrigger(record, session = null)
        )
    }

    @Test
    fun liquidityAndOrders_fromOutcome() {
        val notLiquidity = TouchTurnRunRecord(
            runContext = TouchTurnRunContext(500, TouchTurnSessionStartedBy.MANUAL, BrokerId.EMULATOR),
            marketInputs = TouchTurnRunMarketInputs(),
            decision = TouchTurnSessionDecision(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY),
            stopEvent = TouchTurnStopEvent(TouchTurnSessionStopTrigger.MANUAL),
            milestones = TouchTurnMilestoneTimestamps()
        )
        assertEquals("No", TouchTurnRunRecordUiMapper.liquidityYesNo(notLiquidity))
        assertEquals("No", TouchTurnRunRecordUiMapper.ordersYesNo(notLiquidity))
    }
}
