package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class InferTouchTurnStopTriggerTest {
    @Test
    fun manualStop_infersTradeClosedWhenRoundTripComplete() {
        val trades = listOf(
            SessionTrade(
                execId = "e1",
                orderId = 1,
                permId = 1L,
                parentOrderId = 0,
                side = "BUY",
                quantity = 1,
                price = 100.0,
                time = "2026-05-25T10:00:00"
            ),
            SessionTrade(
                execId = "e2",
                orderId = 2,
                permId = 1L,
                parentOrderId = 1,
                side = "SELL",
                quantity = 1,
                price = 105.0,
                time = "2026-05-25T10:30:00",
                realizedPnL = 5.0
            )
        )
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-25").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-25",
                status = TouchTurnCandleStatus.READY,
                ordersPlacedForSession = true
            )
        )

        assertEquals(
            TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
            inferTouchTurnStopTrigger(
                instance = instance,
                sessionTrades = trades,
                hasOpenPosition = false,
                hasOpenOrders = false
            )
        )
    }
}
