package daytrader.presentation.strategies

import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveMarkPriceResolverTest {
    @Test
    fun isFillReady_requiresPositiveBidAndAsk() {
        assertFalse(LiveMarkPriceResolver.isFillReady(LiveQuote("SPY", bid = 100.0, ask = null, last = 100.0)))
        assertFalse(LiveMarkPriceResolver.isFillReady(LiveQuote("SPY", bid = null, ask = 100.1, last = 100.0)))
        assertTrue(LiveMarkPriceResolver.isFillReady(LiveQuote("SPY", bid = 100.0, ask = 100.1, last = 100.0)))
    }

    @Test
    fun fillReadinessHint_onlyWhenBidAskRequiredAndLastPresent() {
        val quote = LiveQuote("SPY", bid = null, ask = null, last = 100.0)
        assertNull(LiveMarkPriceResolver.fillReadinessHint(quote, requiresBidAskForFills = false))
        assertEquals(
            "Waiting for bid/ask before paper fills",
            LiveMarkPriceResolver.fillReadinessHint(quote, requiresBidAskForFills = true)
        )
    }
}
