package daytrader.broker

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.ActiveExecution
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTradeMatcherTest {
    @Test
    fun fillsForSession_filtersBySymbolAndTimeWindow() {
        val fills = listOf(
            fill(symbol = "AAPL", time = "2026-05-25T10:00:00", execId = "1"),
            fill(symbol = "MSFT", time = "2026-05-25T10:05:00", execId = "2"),
            fill(symbol = "AAPL", time = "2026-05-25T09:00:00", execId = "3"),
            fill(symbol = "AAPL", time = "2026-05-25T11:00:00", execId = "4")
        )
        val matched = SessionTradeMatcher.fillsForSession(
            symbol = "AAPL",
            startedAt = "2026-05-25T10:00:00",
            stoppedAt = "2026-05-25T10:30:00",
            fills = fills
        )
        assertEquals(listOf("1"), matched.map { it.execId })
    }

    @Test
    fun captureForSessionStop_usesInProgressRunWindow() {
        val instance = StrategyDeployment(
            id = "i1",
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            status = DeploymentStatus.RUNNING,
            symbol = "AAPL",
            maxDollars = 500,
            sessionHistory = listOf(
                StrategySession(
                    id = "r1",
                    date = "2026-05-25",
                    startedAt = "2026-05-25T10:00:00",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 500,
                    status = SessionStatus.IN_PROGRESS
                )
            )
        )
        val fills = listOf(
            fill(symbol = "AAPL", time = "2026-05-25T10:15:00", execId = "e1", realized = 12.5)
        )
        val trades = SessionTradeMatcher.captureForSessionStop(
            instance = instance,
            fills = fills,
            stoppedAt = "2026-05-25T10:20:00"
        )
        assertEquals(1, trades.size)
        assertEquals("e1", trades.first().execId)
        assertEquals(12.5, trades.first().realizedPnL)
    }

    @Test
    fun captureForSessionStop_returnsEmptyWhenNoInProgressRun() {
        val instance = StrategyDeployment(
            id = "i1",
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            status = DeploymentStatus.STOPPED,
            symbol = "AAPL",
            maxDollars = 500
        )
        assertTrue(
            SessionTradeMatcher.captureForSessionStop(
                instance = instance,
                fills = listOf(fill(symbol = "AAPL", time = "2026-05-25T10:00:00", execId = "e1")),
                stoppedAt = "2026-05-25T10:30:00"
            ).isEmpty()
        )
    }

    private fun fill(
        symbol: String,
        time: String,
        execId: String,
        realized: Double? = null
    ) = BrokerFill(
        execId = execId,
        orderId = 1,
        permId = 99L,
        parentOrderId = 0,
        symbol = symbol,
        side = "BOT",
        quantity = 10,
        price = 100.0,
        time = time,
        realizedPnL = realized
    )
}
