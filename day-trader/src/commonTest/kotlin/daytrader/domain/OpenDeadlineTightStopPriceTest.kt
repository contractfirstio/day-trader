package daytrader.domain

import daytrader.gateway.AccountPosition
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenDeadlineTightStopPriceTest {
    @Test
    fun compute_shortPlacesBuyStopAboveAsk() {
        val price = OpenDeadlineTightStopPrice.compute(
            position = shortPosition(),
            quote = LiveQuote(symbol = "AAPL", bid = 149.0, ask = 149.05),
            instrument = null,
            symbol = "AAPL"
        )
        assertEquals(149.06, price!!, 0.0001)
    }

    @Test
    fun compute_longPlacesSellStopBelowBid() {
        val price = OpenDeadlineTightStopPrice.compute(
            position = longPosition(),
            quote = LiveQuote(symbol = "AAPL", bid = 149.10, ask = 149.12),
            instrument = null,
            symbol = "AAPL"
        )
        assertEquals(149.09, price!!, 0.0001)
    }

    @Test
    fun isImmediatelyMarketable_shortWhenAskReachesStop() {
        assertTrue(
            OpenDeadlineTightStopPrice.isImmediatelyMarketable(
                position = shortPosition(),
                stopPrice = 149.05,
                quote = LiveQuote(symbol = "AAPL", bid = 149.0, ask = 149.05)
            )
        )
    }

    @Test
    fun isImmediatelyMarketable_longWhenBidReachesStop() {
        assertTrue(
            OpenDeadlineTightStopPrice.isImmediatelyMarketable(
                position = longPosition(),
                stopPrice = 149.10,
                quote = LiveQuote(symbol = "AAPL", bid = 149.10, ask = 149.12)
            )
        )
    }

    @Test
    fun isImmediatelyMarketable_falseWhenStopStillAwayFromMarket() {
        assertFalse(
            OpenDeadlineTightStopPrice.isImmediatelyMarketable(
                position = shortPosition(),
                stopPrice = 149.10,
                quote = LiveQuote(symbol = "AAPL", bid = 149.0, ask = 149.05)
            )
        )
    }

    private fun shortPosition() = AccountPosition(
        account = "DU123",
        symbol = "AAPL",
        companyName = "Apple",
        quantity = -100,
        avgPrice = 150.0,
        marketPrice = 149.0,
        priorClose = 148.0,
        totalUnrealizedPnL = 100.0,
        currency = "USD"
    )

    private fun longPosition() = AccountPosition(
        account = "DU123",
        symbol = "AAPL",
        companyName = "Apple",
        quantity = 100,
        avgPrice = 150.0,
        marketPrice = 149.0,
        priorClose = 148.0,
        totalUnrealizedPnL = 100.0,
        currency = "USD"
    )
}
