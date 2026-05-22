package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolMarketsTest {
    @Test
    fun symbolsMatch_hkNumericVariants() {
        assertTrue(SymbolMarkets.symbolsMatch("700", "700"))
        assertTrue(SymbolMarkets.symbolsMatch("0700", "700"))
        assertTrue(SymbolMarkets.symbolsMatch("700.HK", "0700"))
    }

    @Test
    fun symbolsMatch_usTickers_caseInsensitive() {
        assertTrue(SymbolMarkets.symbolsMatch("nvda", "NVDA"))
        assertFalse(SymbolMarkets.symbolsMatch("NVDA", "700"))
    }
}
