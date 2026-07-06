package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTradeCommissionTest {
    @Test
    fun sessionCommissionTotal_sumsPerFillCommissions() {
        val trades = ibRoundTripTrades(exitNetRealizedPnL = 499.30)

        assertEquals(0.70, trades.sessionCommissionTotal())
    }

    @Test
    fun sessionDisplayPnL_usesIbNetRealizedWithoutSubtractingCommissionAgain() {
        val trades = ibRoundTripTrades(exitNetRealizedPnL = 499.30)

        assertEquals(499.30, trades.sessionRealizedPnL())
        assertEquals(499.30, trades.sessionDisplayPnL())
        assertEquals(500.0, trades.sessionGrossPricePnL())
    }

    @Test
    fun sessionDisplayPnL_matchesIbTradeLineForHkShortStopLoss() {
        val trades = listOf(
            trade(
                execId = "entry",
                parentOrderId = 0,
                commission = 54.6893,
                realizedPnL = 0.0,
                price = 149.0,
            ),
            trade(
                execId = "exit",
                parentOrderId = 288,
                commission = 56.00413,
                realizedPnL = -490.69343,
                price = 150.9,
                side = "BOT",
            ),
        )

        assertEquals(-490.69343, trades.sessionDisplayPnL())
        assertEquals(-380.0, trades.sessionGrossPricePnL(), 0.01)
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
            sessionTrades = ibRoundTripTrades(exitNetRealizedPnL = 499.30),
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
                pnl = 499.30,
                trades = 1,
                maxAtRisk = 1000,
                status = SessionStatus.CLOSED,
                positionOpened = true,
                sessionTrades = ibRoundTripTrades(exitNetRealizedPnL = 499.30),
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
                trade(commission = null, realizedPnL = 499.30, parentOrderId = 1),
            ),
        )

        assertEquals(499.30, session.effectivePnL())
    }

    private fun ibRoundTripTrades(exitNetRealizedPnL: Double) = listOf(
        trade(commission = 0.35, realizedPnL = 0.0, parentOrderId = 0),
        trade(commission = 0.35, realizedPnL = exitNetRealizedPnL, parentOrderId = 1),
    )

    private fun trade(
        commission: Double?,
        realizedPnL: Double,
        parentOrderId: Int = 0,
        execId: String = "exec-$commission-$realizedPnL",
        price: Double = 100.0,
        side: String = if (parentOrderId == 0) "BUY" else "SELL",
    ) = SessionTrade(
        execId = execId,
        orderId = if (parentOrderId == 0) 1 else 2,
        permId = if (parentOrderId == 0) 1L else 2L,
        parentOrderId = parentOrderId,
        side = side,
        quantity = 10,
        price = price,
        time = "2026-05-25T10:00:00",
        currency = "USD",
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
