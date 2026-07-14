package daytrader.presentation.strategies

import daytrader.domain.OhlcBar
import daytrader.domain.SessionStatus
import daytrader.domain.SessionTrade
import daytrader.domain.StrategySession
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnSessionChronologyMapperTest {

    @Test
    fun fromClosedRun_ordersEventsChronologicallyWithKeyDetails() {
        val closed = StrategySession(
            id = "run-1",
            date = "2026-05-22",
            startedAt = "2026-05-22T09:30:01",
            stoppedAt = "2026-05-22T10:12:44",
            pnl = 12.5,
            trades = 1,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            sessionTrades = listOf(
                SessionTrade(
                    execId = "e1",
                    orderId = 1,
                    permId = 1,
                    parentOrderId = 0,
                    side = "SELL",
                    quantity = 4,
                    price = 110.0,
                    time = "2026-05-22T09:48:05",
                    currency = "HKD",
                    commission = 1.0,
                    realizedPnL = 0.0,
                ),
                SessionTrade(
                    execId = "e2",
                    orderId = 2,
                    permId = 2,
                    parentOrderId = 1,
                    side = "BUY",
                    quantity = 4,
                    price = 103.0,
                    time = "2026-05-22T10:11:20",
                    currency = "HKD",
                    commission = 1.0,
                    realizedPnL = 26.0,
                ),
            ),
            touchTurnRunRecord = TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 500,
                    startedBy = TouchTurnSessionStartedBy.AUTO_MARKET_OPEN,
                    brokerId = BrokerId.EMULATOR,
                ),
                marketInputs = TouchTurnRunMarketInputs(
                    openingBar = OhlcBar(100.0, 110.0, 99.0, 108.0, "20260522  09:30:00"),
                    dailyAtr14 = 4.0,
                    currencyCode = "HKD",
                    marketZoneId = "Asia/Hong_Kong",
                ),
                decision = TouchTurnSessionDecision(
                    outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                    plannedQuantity = 4,
                    plannedBracket = TouchTurnPlannedBracket(
                        side = TouchTurnTradeSide.SHORT,
                        entry = 110.0,
                        stopLoss = 113.0,
                        takeProfit = 103.0,
                    ),
                ),
                stopEvent = TouchTurnStopEvent(
                    stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                    brokerUnrealizedPnLAtStop = 12.5,
                ),
                milestones = TouchTurnMilestoneTimestamps(
                    startingSessionAt = "2026-05-22T09:30:01",
                    dataReadyAt = "2026-05-22T09:30:12",
                    barClosedAt = "2026-05-22T09:45:00",
                    liquidityEvaluatedAt = "2026-05-22T09:45:02",
                    closeConfirmedAt = "2026-05-22T09:45:05",
                    ordersPlacedAt = "2026-05-22T09:45:08",
                    positionOpenedAt = "2026-05-22T09:48:05",
                    closingSessionAt = "2026-05-22T10:12:44",
                ),
            ),
        )

        val ui = TouchTurnSessionChronologyMapper.fromClosedRun(closed)
        val titles = ui.events.map { it.title }

        assertEquals(
            listOf(
                "Session started",
                "Market data ready",
                "Opening bar closed",
                "Liquidity evaluated",
                "Close confirmed",
                "Orders placed",
                "Position opened",
                "Entry fill",
                "Exit fill",
                "Session closed",
            ),
            titles,
        )
        assertEquals(
            listOf(
                "09:30",
                "09:30",
                "09:45",
                "09:45",
                "09:45",
                "09:45",
                "09:48",
                "09:48",
                "10:11",
                "10:12",
            ),
            ui.events.map { it.timeLabel },
        )
        assertTrue(ui.events[0].detail!!.contains("Auto"))
        assertTrue(ui.events[0].detail!!.contains("500"))
        assertTrue(ui.events[1].detail!!.contains("110"))
        assertTrue(ui.events[3].detail!!.contains("Short") || ui.events[3].detail!!.contains("liquid"))
        assertTrue(ui.events[5].detail!!.contains("110"))
        assertTrue(ui.events[7].detail!!.contains("4"))
        assertTrue(ui.events[8].detail!!.contains("26") || ui.events[8].detail!!.contains("+"))
        assertTrue(ui.events[9].detail!!.contains("trade") || ui.events[9].detail!!.contains("Trade"))
    }

    @Test
    fun fromClosedRun_noTrade_includesDecisionAtRulesTime() {
        val closed = StrategySession(
            id = "run-2",
            date = "2026-05-22",
            startedAt = "2026-05-22T09:30:01",
            stoppedAt = "2026-05-22T09:45:10",
            pnl = 0.0,
            trades = 0,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            touchTurnRunRecord = TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 500,
                    startedBy = TouchTurnSessionStartedBy.MANUAL,
                    brokerId = BrokerId.INTERACTIVE_BROKERS,
                ),
                marketInputs = TouchTurnRunMarketInputs(
                    openingBar = OhlcBar(100.0, 100.5, 99.8, 100.2),
                    currencyCode = "USD",
                ),
                decision = TouchTurnSessionDecision(
                    outcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                ),
                stopEvent = TouchTurnStopEvent(
                    stopTrigger = TouchTurnSessionStopTrigger.NO_TRADE_DECISION,
                ),
                milestones = TouchTurnMilestoneTimestamps(
                    startingSessionAt = "2026-05-22T09:30:01",
                    dataReadyAt = "2026-05-22T09:30:12",
                    barClosedAt = "2026-05-22T09:45:00",
                    liquidityEvaluatedAt = "2026-05-22T09:45:02",
                    closingSessionAt = "2026-05-22T09:45:10",
                ),
            ),
        )

        val ui = TouchTurnSessionChronologyMapper.fromClosedRun(closed)
        val titles = ui.events.map { it.title }
        assertEquals(
            listOf(
                "Session started",
                "Market data ready",
                "Opening bar closed",
                "Liquidity evaluated",
                "Decision",
                "Session closed",
            ),
            titles,
        )
        assertTrue(ui.events.first { it.title == "Decision" }.detail!!.contains("liquid", ignoreCase = true))
    }

    @Test
    fun fromClosedRun_emptyWithoutRunRecord_fallsBackToSessionBounds() {
        val closed = StrategySession(
            id = "run-3",
            date = "2026-05-22",
            startedAt = "2026-05-22T09:31:00",
            stoppedAt = "2026-05-22T09:40:00",
            pnl = 0.0,
            trades = 0,
            maxAtRisk = 250,
            status = SessionStatus.CLOSED,
        )
        val ui = TouchTurnSessionChronologyMapper.fromClosedRun(closed)
        assertEquals(listOf("Session started", "Session closed"), ui.events.map { it.title })
        assertEquals(listOf("09:31", "09:40"), ui.events.map { it.timeLabel })
    }
}
