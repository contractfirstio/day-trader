package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTradeCommissionTest {
    @Test
    fun sessionCommissionTotal_sumsPerFillCommissions() {
        val trades = listOf(
            trade(commission = 0.35, realizedPnL = 0.0),
            trade(commission = 0.35, realizedPnL = 500.0),
        )

        assertEquals(0.70, trades.sessionCommissionTotal())
    }

    @Test
    fun sessionNetPnL_subtractsCommissionFromGrossRealized() {
        val trades = listOf(
            trade(commission = 0.35, realizedPnL = 0.0),
            trade(commission = 0.35, realizedPnL = 500.0),
        )

        assertEquals(500.0, trades.sessionRealizedPnL())
        assertEquals(499.30, trades.sessionNetPnL())
    }

    @Test
    fun effectivePnL_prefersNetFromTradesOverStoredGrossPnl() {
        val session = StrategySession(
            id = "s1",
            date = "2026-05-25",
            startedAt = "2026-05-25T10:00:00",
            stoppedAt = "2026-05-25T11:00:00",
            pnl = 500.0,
            trades = 1,
            maxAtRisk = 1000,
            status = SessionStatus.CLOSED,
            sessionTrades = listOf(
                trade(commission = 0.35, realizedPnL = 0.0),
                trade(commission = 0.35, realizedPnL = 500.0),
            ),
        )

        assertEquals(499.30, session.effectivePnL())
    }

    @Test
    fun rollups_useNetWhenCommissionComplete() {
        val sessions = listOf(
            StrategySession(
                id = "s1",
                date = "2026-05-25",
                startedAt = "2026-05-25T10:00:00",
                stoppedAt = "2026-05-25T11:00:00",
                pnl = 500.0,
                trades = 1,
                maxAtRisk = 1000,
                status = SessionStatus.CLOSED,
                positionOpened = true,
                sessionTrades = listOf(
                    trade(commission = 0.35, realizedPnL = 0.0),
                    trade(commission = 0.35, realizedPnL = 500.0),
                ),
            )
        )

        assertEquals(499.30, sessions.rollups("2026-05-25").totalPnl)
    }

    @Test
    fun effectivePnL_prefersStoredNetWhenPersistedFillsLostCommission() {
        val session = StrategySession(
            id = "s1",
            date = "2026-05-25",
            startedAt = "2026-05-25T10:00:00",
            stoppedAt = "2026-05-25T11:00:00",
            pnl = 499.30,
            trades = 1,
            maxAtRisk = 1000,
            status = SessionStatus.CLOSED,
            sessionTrades = listOf(
                trade(commission = null, realizedPnL = 0.0),
                trade(commission = null, realizedPnL = 500.0),
            ),
        )

        assertEquals(499.30, session.effectivePnL())
    }

    private fun trade(commission: Double?, realizedPnL: Double) = SessionTrade(
        execId = "exec-$commission-$realizedPnL",
        orderId = 1,
        permId = 1L,
        parentOrderId = 0,
        side = "BUY",
        quantity = 10,
        price = 100.0,
        time = "2026-05-25T10:00:00",
        currency = "USD",
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
