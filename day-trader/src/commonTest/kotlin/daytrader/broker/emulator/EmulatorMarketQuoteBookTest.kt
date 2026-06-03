package daytrader.broker.emulator

import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorMarketQuoteBookTest {

    @Test
    fun buyLimit_fillsWhenAskAtOrBelowLimit() {
        assertTrue(EmulatorMarketQuoteBook.buyLimitFillable(ask = 99.9, limit = 100.0))
        assertFalse(EmulatorMarketQuoteBook.buyLimitFillable(ask = 100.1, limit = 100.0))
    }

    @Test
    fun sellLimit_fillsWhenBidAtOrAboveLimit() {
        assertTrue(EmulatorMarketQuoteBook.sellLimitFillable(bid = 100.1, limit = 100.0))
        assertFalse(EmulatorMarketQuoteBook.sellLimitFillable(bid = 99.9, limit = 100.0))
    }

    @Test
    fun stops_useBidForSellStopAndAskForBuyStop() {
        assertTrue(EmulatorMarketQuoteBook.sellStopTriggered(bid = 98.0, stop = 99.0))
        assertTrue(EmulatorMarketQuoteBook.buyStopTriggered(ask = 102.0, stop = 101.0))
    }

    @Test
    fun fromLiveQuote_requiresBidAndAsk() {
        assertNull(EmulatorMarketQuote.fromLiveQuote(LiveQuote(symbol = "AAPL", last = 100.0)))
        val book = EmulatorMarketQuote.fromLiveQuote(
            LiveQuote(symbol = "AAPL", bid = 99.8, ask = 100.2, last = 100.0)
        )!!
        assertEquals(99.8, book.bid)
        assertEquals(100.2, book.ask)
        assertEquals(100.0, book.last)
    }

    @Test
    fun fromLiveQuote_mergesPartialUpdates() {
        val initial = EmulatorMarketQuote.fromLiveQuote(
            LiveQuote(symbol = "AAPL", bid = 100.0, ask = 100.4, last = 100.2)
        )!!
        val merged = EmulatorMarketQuote.fromLiveQuote(
            LiveQuote(symbol = "AAPL", last = 99.5),
            existing = initial
        )!!
        assertEquals(100.0, merged.bid)
        assertEquals(100.4, merged.ask)
        assertEquals(99.5, merged.last)
    }

    @Test
    fun fromLiveQuote_carriesTickVolumeFromIbFeed() {
        val book = EmulatorMarketQuote.fromLiveQuote(
            LiveQuote(symbol = "AAPL", bid = 100.0, ask = 100.4, last = 100.2, tickVolume = 750.0)
        )!!
        assertEquals(750.0, book.toLiveQuote("AAPL").tickVolume)
    }
}
