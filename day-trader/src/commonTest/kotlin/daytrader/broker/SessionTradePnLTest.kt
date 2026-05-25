package daytrader.broker

import daytrader.domain.SessionTrade
import daytrader.gateway.AccountPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTradePnLTest {
    @Test
    fun totalSessionPnL_sumsRealizedAndUnrealized() {
        val trades = listOf(
            SessionTrade(
                execId = "1",
                orderId = 1,
                permId = 1L,
                parentOrderId = 0,
                side = "SELL",
                quantity = 5,
                price = 100.0,
                time = "t1",
                realizedPnL = 25.0
            )
        )
        val total = SessionTradePnL.totalSessionPnL(trades, unrealizedPnL = 10.0)
        assertEquals(35.0, total)
    }

    @Test
    fun unrealizedForSymbol_matchesPosition() {
        val position = AccountPosition(
            account = "A",
            symbol = "AAPL",
            companyName = "Apple",
            quantity = 5,
            avgPrice = 100.0,
            marketPrice = 101.0,
            priorClose = 99.0,
            totalUnrealizedPnL = 5.0,
            currency = "USD"
        )
        assertEquals(5.0, SessionTradePnL.unrealizedForSymbol("AAPL", position))
    }
}
