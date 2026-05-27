package daytrader.broker.emulator

import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorQuoteBookTest {

    @Test
    fun syntheticMode_allowsFillsOnceQuoteExists() {
        val book = EmulatorQuoteBook(EmulatorPricingSource.SYNTHETIC)
        book.seedSymbol("AAPL", sampleQuote())
        assertTrue(book.canTriggerFills("AAPL"))
    }

    @Test
    fun liveExchangeMode_requiresBidAndAskBeforeFills() {
        val book = EmulatorQuoteBook(EmulatorPricingSource.LIVE_EXCHANGE)
        book.ingestExternal("AAPL", LiveQuote(symbol = "AAPL", last = 100.0))
        assertFalse(book.canTriggerFills("AAPL"))

        book.ingestExternal("AAPL", LiveQuote(symbol = "AAPL", bid = 99.8, ask = 100.2, last = 100.0))
        assertTrue(book.canTriggerFills("AAPL"))
    }

    @Test
    fun syntheticMode_ignoresExternalIngest() {
        val book = EmulatorQuoteBook(EmulatorPricingSource.SYNTHETIC)
        assertNull(
            book.ingestExternal("AAPL", LiveQuote(symbol = "AAPL", bid = 99.8, ask = 100.2, last = 100.0))
        )
        assertFalse(book.canTriggerFills("AAPL"))
    }

    @Test
    fun liveExchangeMode_acceptsExternalIngest() {
        val book = EmulatorQuoteBook(EmulatorPricingSource.LIVE_EXCHANGE)
        assertNotNull(
            book.ingestExternal("AAPL", LiveQuote(symbol = "AAPL", bid = 99.8, ask = 100.2, last = 100.0))
        )
    }

    private fun sampleQuote() = EmulatorMarketQuote(
        last = 100.0,
        bid = 99.9,
        ask = 100.1,
        halfSpread = 0.1
    )
}
