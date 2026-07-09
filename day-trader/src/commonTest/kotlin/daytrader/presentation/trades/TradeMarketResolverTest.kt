package daytrader.presentation.trades

import daytrader.domain.RthMarketSessions
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeMarketResolverTest {
    @Test
    fun resolvesHongKongFromNumericSymbol() {
        val fill = sampleFill(symbol = "0700", currency = "HKD")
        assertEquals(RthMarketSessions.HK.zoneId, TradeMarketResolver.zoneId(fill))
        assertEquals("HK", TradeMarketResolver.shortLabel(fill))
    }

    @Test
    fun resolvesUsFromUsdEquity() {
        val fill = sampleFill(symbol = "AAPL", currency = "USD")
        assertEquals(RthMarketSessions.US.zoneId, TradeMarketResolver.zoneId(fill))
        assertEquals("US", TradeMarketResolver.shortLabel(fill))
    }

    @Test
    fun resolvesUkFromGbpCurrency() {
        val fill = sampleFill(symbol = "VOD", currency = "GBP")
        assertEquals(RthMarketSessions.EUR.zoneId, TradeMarketResolver.zoneId(fill))
        assertEquals("UK", TradeMarketResolver.shortLabel(fill))
    }

    private fun sampleFill(symbol: String, currency: String) = BrokerFill(
        execId = "x",
        orderId = 1,
        permId = 1L,
        parentOrderId = 0,
        symbol = symbol,
        side = "BOT",
        quantity = 1,
        price = 1.0,
        time = "2026-07-07",
        currency = currency,
    )
}
