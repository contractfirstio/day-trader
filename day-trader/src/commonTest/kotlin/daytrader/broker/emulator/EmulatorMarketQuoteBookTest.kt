package daytrader.broker.emulator

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
