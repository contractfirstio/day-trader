package daytrader.domain

import daytrader.gateway.BrokerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnBracketExecutionTest {

    @Test
    fun resolveFromTrades_winUsesExitFillPnlNotSessionPnl() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 10, price = 100.0, realized = null),
            trade(parentOrderId = 10, orderId = 11, price = 110.0, realized = 50.0)
        )
        val legs = TouchTurnBracketExecution.resolveFromTrades(
            trades = trades,
            plannedBracket = bracket,
            bracketSetup = null,
            sessionPnl = -99.0
        )
        assertTrue(TouchTurnOrderRole.ENTRY in legs)
        assertTrue(TouchTurnOrderRole.TAKE_PROFIT in legs)
        assertEquals(false, TouchTurnOrderRole.STOP_LOSS in legs)
    }

    @Test
    fun resolveFromTrades_lossUsesExitFillPnl() {
        val bracket = TouchTurnPlannedBracket(
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 10, price = 100.0, realized = null),
            trade(parentOrderId = 10, orderId = 11, price = 108.0, realized = -12.0)
        )
        val legs = TouchTurnBracketExecution.resolveFromTrades(trades, bracket, null, sessionPnl = 99.0)
        assertTrue(TouchTurnOrderRole.STOP_LOSS in legs)
        assertEquals(false, TouchTurnOrderRole.TAKE_PROFIT in legs)
    }

    @Test
    fun postStopSession_prefersTradedRunForAnalysisContext() {
        val traded = StrategySession(
            id = "traded",
            date = "2026-05-21",
            stoppedAt = "2026-05-21T10:00:00",
            pnl = 25.0,
            trades = 2,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            touchTurnMilestones = TouchTurnMilestoneTimestamps(),
            sessionTrades = listOf(
                trade(parentOrderId = 0, orderId = 1, price = 100.0, realized = null),
                trade(parentOrderId = 1, orderId = 2, price = 110.0, realized = 25.0)
            ),
            touchTurnRunRecord = minimalRunRecord(
                plannedBracket = TouchTurnPlannedBracket(
                    TouchTurnTradeSide.LONG, 100.0, 95.0, 110.0
                ),
                executedLegs = listOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.TAKE_PROFIT)
            )
        )
        val empty = StrategySession(
            id = "empty",
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
            sessionHistory = listOf(empty, traded)
        )
        val context = deployment.touchTurnAnalysisSession()
        assertEquals(
            listOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.TAKE_PROFIT),
            context?.executedBracketLegs
        )
        assertEquals(2, deployment.touchTurnRecapSessionTrades().size)
    }

    private fun trade(
        parentOrderId: Int,
        orderId: Int,
        price: Double,
        realized: Double?
    ) = SessionTrade(
        execId = "e$orderId",
        orderId = orderId,
        permId = orderId + 100L,
        parentOrderId = parentOrderId,
        side = if (parentOrderId == 0) "BOT" else "SLD",
        quantity = 10,
        price = price,
        time = "2026-05-22T10:00:00",
        currency = "USD",
        realizedPnL = realized
    )

    private fun minimalRunRecord(
        plannedBracket: TouchTurnPlannedBracket,
        executedLegs: List<TouchTurnOrderRole>
    ) = TouchTurnRunRecord(
        runContext = TouchTurnRunContext(500, TouchTurnSessionStartedBy.MANUAL, BrokerId.EMULATOR),
        marketInputs = TouchTurnRunMarketInputs(
            openingBar = OhlcBar(100.0, 102.0, 99.0, 101.0, "20260522  09:30:00"),
            adr14 = 4.0
        ),
        decision = TouchTurnSessionDecision(
            outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            plannedBracket = plannedBracket,
            executedLegs = executedLegs
        ),
        stopEvent = TouchTurnStopEvent(TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN),
        milestones = TouchTurnMilestoneTimestamps()
    )
}
