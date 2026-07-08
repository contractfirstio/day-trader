package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

class DeploymentSessionStopLogicTest {
    @Test
    fun shouldStopAfterNoTradeDecision_trueForTouchTurnNoTradeOutcomes() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        val outcomes = listOf(
            TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_COLOR_SKIPPED,
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_SHAPE_TRIGGER_SKIPPED,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
            TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
            TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
        )
        outcomes.forEach { outcome ->
            assertTrue(
                DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(
                    instance.copy(touchTurnSession = touchTurnSessionWithDecision(outcome))
                )
            )
        }
    }

    @Test
    fun shouldStopAfterNoTradeDecision_falseWhenTradeSubmittedOrNoDecision() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(
                instance.copy(
                    touchTurnSession = touchTurnSessionWithDecision(
                        TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
                    )
                )
            )
        )
        assertFalse(DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance))
    }

    @Test
    fun shouldStopAfterTradeOutcome_whenFlatWithEntryAndExitFills() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        val trades = listOf(sessionTrade(parentOrderId = 0), sessionTrade(parentOrderId = 1, execId = "e-exit"))
        assertTrue(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
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
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        val trades = listOf(sessionTrade(parentOrderId = 0), sessionTrade(parentOrderId = 1, execId = "e-exit"))
        val session = touchTurnSessionWithOrdersPlaced()
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = session),
                sessionTrades = trades,
                hasOpenPosition = true,
                hasOpenOrders = false
            )
        )
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = session),
                sessionTrades = trades,
                hasOpenPosition = false,
                hasOpenOrders = true
            )
        )
    }

    @Test
    fun shouldStopAfterTradeOutcome_falseWithOnlyEntryFill() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25")
        assertFalse(
            DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance.copy(touchTurnSession = touchTurnSessionWithOrdersPlaced()),
                sessionTrades = listOf(sessionTrade(parentOrderId = 0)),
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }

    @Test
    fun sessionDateForRunningInstance_prefersTouchTurnSessionDate() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "VOD",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-26",
                status = TouchTurnCandleStatus.READY,
                marketZoneId = "Europe/London"
            )
        )

        assertEquals("2026-05-26", DeploymentSessionStopLogic.sessionDateForRunningInstance(instance))
    }

    @Test
    fun evaluateDeadlineForInstance_usesTouchTurnSessionMarketZone() {
        val sessionDate = "2026-05-26"
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING,
            marketZoneId = "America/New_York"
        ).onSessionStarted("2026-05-26").copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = sessionDate,
                status = TouchTurnCandleStatus.READY,
                marketZoneId = "Europe/London",
                rules = rules
            )
        )

        val open = assertNotNull(
            TouchTurnLogic.marketOpenEpochMillis(
                sessionDateIso = sessionDate,
                marketZoneId = "Europe/London"
            )
        )
        assertEquals(
            DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE,
            DeploymentSessionStopLogic.evaluateDeadlineForInstance(
                instance = instance,
                nowEpochMillis = open + 90 * 60_000L
            )
        )
    }
}

private fun touchTurnSessionWithOrdersPlaced(): TouchTurnSessionContext =
    TouchTurnSessionContext(
        sessionDate = "2026-05-25",
        status = TouchTurnCandleStatus.READY,
        ordersPlacedForSession = true
    )

private fun touchTurnSessionWithDecision(outcome: TouchTurnSessionOutcome): TouchTurnSessionContext =
    TouchTurnSessionContext(
        sessionDate = "2026-05-25",
        status = TouchTurnCandleStatus.READY,
        decisionOutcome = outcome
    )
