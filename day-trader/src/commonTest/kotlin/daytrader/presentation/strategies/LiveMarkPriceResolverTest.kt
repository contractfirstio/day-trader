package daytrader.presentation.strategies

import daytrader.domain.TouchTurnTradeSide
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

    @Test
    fun resolveForTouchTurnChart_usesLastBeforeOrdersPlaced() {
        val quote = LiveQuote("9988", bid = 109.6, ask = 109.7, last = 109.6)
        val price = LiveMarkPriceResolver.resolveForTouchTurnChart(
            symbol = "9988",
            positions = emptyList(),
            quotes = mapOf("9988" to quote),
            entrySide = TouchTurnTradeSide.SHORT,
            ordersPlaced = false,
            inPosition = false
        )
        assertEquals(109.6, price)
    }

    @Test
    fun resolveForTouchTurnChart_shortAwaitingEntryUsesBid() {
        val quote = LiveQuote("9988", bid = 109.6, ask = 109.7, last = 109.5)
        val price = LiveMarkPriceResolver.resolveForTouchTurnChart(
            symbol = "9988",
            positions = emptyList(),
            quotes = mapOf("9988" to quote),
            entrySide = TouchTurnTradeSide.SHORT,
            ordersPlaced = true,
            inPosition = false
        )
        assertEquals(109.6, price)
    }

    @Test
    fun resolveForTouchTurnChart_shortInPositionUsesAsk() {
        val quote = LiveQuote("9988", bid = 109.6, ask = 109.7, last = 109.6)
        val price = LiveMarkPriceResolver.resolveForTouchTurnChart(
            symbol = "9988",
            positions = emptyList(),
            quotes = mapOf("9988" to quote),
            entrySide = TouchTurnTradeSide.SHORT,
            ordersPlaced = true,
            inPosition = true
        )
        assertEquals(109.7, price)
    }

    @Test
    fun resolveForTouchTurnChart_longInPositionUsesBid() {
        val quote = LiveQuote("LLOY", bid = 99.60, ask = 99.64, last = 99.62)
        val price = LiveMarkPriceResolver.resolveForTouchTurnChart(
            symbol = "LLOY",
            positions = emptyList(),
            quotes = mapOf("LLOY" to quote),
            entrySide = TouchTurnTradeSide.LONG,
            ordersPlaced = true,
            inPosition = true
        )
        assertEquals(99.60, price)
    }
}
