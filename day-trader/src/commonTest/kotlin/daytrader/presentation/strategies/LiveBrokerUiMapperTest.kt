package daytrader.presentation.strategies

import daytrader.gateway.AccountPosition
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveBrokerUiMapperTest {
    @Test
    fun forSymbol_omitsMarketQuotesWhenDisabled() {
        val state = LiveBrokerUiMapper.forSymbol(
            symbol = "AAPL",
            positions = listOf(
                AccountPosition(
                    account = "DU123",
                    symbol = "AAPL",
                    companyName = "Apple Inc.",
                    quantity = 100,
                    avgPrice = 150.0,
                    marketPrice = 155.0,
                    bidPrice = 154.9,
                    askPrice = 155.1,
                    lastTradePrice = 155.0,
                    priorClose = 150.0,
                    totalUnrealizedPnL = 500.0,
                    currency = "USD",
                )
            ),
            quotes = mapOf("AAPL" to LiveQuote(symbol = "AAPL", bid = 154.8, ask = 155.2, last = 155.0)),
            openOrders = emptyList(),
            connection = GatewayConnectionState.Connected,
            includeMarketQuotes = false,
        )

        assertNull(state.formattedBid)
        assertNull(state.formattedAsk)
        assertNull(state.formattedLast)
        assertEquals("—", state.position?.formattedMarketPrice)
    }
}
