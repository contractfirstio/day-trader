package daytrader.domain

import daytrader.gateway.WorkingOrder
import daytrader.data.StrategyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun sessionTrade(parentOrderId: Int, execId: String = "e-$parentOrderId") = SessionTrade(
    execId = execId,
    orderId = parentOrderId + 1,
    permId = 1L,
    parentOrderId = parentOrderId,
    side = "BUY",
    quantity = 1,
    price = 100.0,
    time = "2026-05-25T10:00:00",
    realizedPnL = if (parentOrderId != 0) 5.0 else null
)

class InstanceRunStopLogicTest {
    @Test
    fun stopAfterMinOpen_isConfiguredPerStrategyType() {
        assertEquals(90, StrategyCatalog.stopAfterMinOpen(StrategyType.TOUCH_AND_TURN_SCALPER))
        assertEquals(90, StrategyCatalog.stopAfterMinOpen(StrategyType.QUICK_FLIP_SCALPER))
    }

    @Test
    fun evaluate_continuesBeforeDeadline() {
        val open = 1_000_000L
        val close = open + 7 * 60 * 60 * 1000
        assertEquals(
            SessionStopAction.CONTINUE,
            InstanceRunStopLogic.evaluate(
                nowEpochMillis = open + 30 * 60_000,
                sessionOpenEpochMillis = open,
                marketCloseEpochMillis = close,
                stopAfterMinOpen = 90,
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun evaluate_stopsFlatAfterDeadlineWhenNoPositionOrOrders() {
        val open = 1_000_000L
        val close = open + 7 * 60 * 60 * 1000
        val deadline = open + 90 * 60_000
        assertEquals(
            SessionStopAction.STOP_FLAT_AFTER_OPEN,
            InstanceRunStopLogic.evaluate(
                nowEpochMillis = deadline,
                sessionOpenEpochMillis = open,
                marketCloseEpochMillis = close,
                stopAfterMinOpen = 90,
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun evaluate_continuesAfterDeadlineWithOpenOrdersOnly() {
        val open = 1_000_000L
        val close = open + 7 * 60 * 60 * 1000
        val deadline = open + 90 * 60_000
        assertEquals(
            SessionStopAction.CONTINUE,
            InstanceRunStopLogic.evaluate(
                nowEpochMillis = deadline + 60_000,
                sessionOpenEpochMillis = open,
                marketCloseEpochMillis = close,
                stopAfterMinOpen = 90,
                hasOpenPosition = false,
                hasOpenOrders = true
            )
        )
    }

    @Test
    fun evaluate_stopsAtMarketCloseWhenPositionHeldAfterDeadline() {
        val open = 1_000_000L
        val close = open + 7 * 60 * 60 * 1000
        val afterDeadline = open + 90 * 60_000 + 60_000
        assertEquals(
            SessionStopAction.CONTINUE,
            InstanceRunStopLogic.evaluate(
                nowEpochMillis = afterDeadline,
                sessionOpenEpochMillis = open,
                marketCloseEpochMillis = close,
                stopAfterMinOpen = 90,
                hasOpenPosition = true,
                hasOpenOrders = false
            )
        )
        assertEquals(
            SessionStopAction.STOP_AT_MARKET_CLOSE,
            InstanceRunStopLogic.evaluate(
                nowEpochMillis = close,
                sessionOpenEpochMillis = open,
                marketCloseEpochMillis = close,
                stopAfterMinOpen = 90,
                hasOpenPosition = true,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun marketCloseDeadline_isAfterSessionOpen() {
        val barTime = "20250522  09:30:00"
        val open = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", "Asia/Hong_Kong", barTime)!!
        val close = TouchTurnLogic.marketCloseEpochMillis("2025-05-22", "Asia/Hong_Kong")!!
        assertTrue(close > open)
        assertEquals(
            open + 90 * 60_000,
            InstanceRunStopLogic.stopAfterOpenDeadlineEpochMillis(open, 90)
        )
    }

    @Test
    fun shouldStopAfterTradeOutcome_whenFlatWithEntryAndExitFills() {
        val instance = defaultStrategyInstance(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = InstanceStatus.RUNNING
        ).onRunStarted("2026-05-25")
        val trades = listOf(sessionTrade(parentOrderId = 0), sessionTrade(parentOrderId = 1, execId = "e-exit"))
        assertTrue(
            InstanceRunStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(
                    touchTurnSession = touchTurnSessionWithOrdersPlaced()
                ),
                sessionTrades = trades,
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun shouldStopAfterTradeOutcome_falseWhilePositionOrOrdersOpen() {
        val instance = defaultStrategyInstance(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = InstanceStatus.RUNNING
        ).onRunStarted("2026-05-25")
        val trades = listOf(sessionTrade(parentOrderId = 0), sessionTrade(parentOrderId = 1, execId = "e-exit"))
        val session = touchTurnSessionWithOrdersPlaced()
        assertFalse(
            InstanceRunStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = session),
                sessionTrades = trades,
                hasOpenPosition = true,
                hasOpenOrders = false
            )
        )
        assertFalse(
            InstanceRunStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = session),
                sessionTrades = trades,
                hasOpenPosition = false,
                hasOpenOrders = true
            )
        )
    }

    @Test
    fun shouldStopAfterTradeOutcome_falseWithOnlyEntryFill() {
        val instance = defaultStrategyInstance(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = InstanceStatus.RUNNING
        ).onRunStarted("2026-05-25")
        assertFalse(
            InstanceRunStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = touchTurnSessionWithOrdersPlaced()),
                sessionTrades = listOf(sessionTrade(parentOrderId = 0)),
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun evaluateForInstance_matchesSymbolOpenOrders() {
        val instance = defaultStrategyInstance(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "TSLA",
            maxDollars = 500,
            status = InstanceStatus.RUNNING
        ).onRunStarted("2025-05-22")
        val open = InstanceRunStopLogic.sessionOpenEpochMillis(instance, "2025-05-22")!!
        val close = InstanceRunStopLogic.marketCloseEpochMillis("2025-05-22", "America/New_York")!!
        val action = InstanceRunStopLogic.evaluateForInstance(
            instance = instance,
            stopAfterMinOpen = 90,
            positions = emptyList(),
            openOrders = listOf(
                WorkingOrder(
                    orderId = 1,
                    symbol = "TSLA",
                    action = "BUY",
                    quantity = 1,
                    filled = 0,
                    remaining = 1,
                    orderType = "LMT",
                    limitPrice = 100.0,
                    stopPrice = null,
                    status = "Submitted",
                    currency = "USD"
                )
            ),
            nowEpochMillis = open + 90 * 60_000
        )
        assertEquals(SessionStopAction.CONTINUE, action)
    }
}

private fun touchTurnSessionWithOrdersPlaced(): TouchTurnSessionContext =
    TouchTurnSessionContext(
        sessionDate = "2026-05-25",
        status = TouchTurnCandleStatus.READY,
        ordersPlacedForSession = true
    )
