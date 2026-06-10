package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentPriceScaleTest {

    @Test
    fun quotesInMinorUnits_lseGbp_isTrue() {
        assertTrue(InstrumentPriceScale.quotesInMinorUnits("GBP", "LSE"))
        assertTrue(InstrumentPriceScale.quotesInMinorUnits("GBX"))
        assertFalse(InstrumentPriceScale.quotesInMinorUnits("GBP"))
        assertFalse(InstrumentPriceScale.quotesInMinorUnits("GBP", "ICEEU"))
    }

    @Test
    fun resolvedListingExch_infersLseForUkSession() {
        assertEquals(
            "LSE",
            InstrumentPriceScale.resolvedListingExch(
                currency = "GBP",
                marketZoneId = RthMarketSessions.EUR.zoneId,
                primaryExch = null
            )
        )
    }

    @Test
    fun unrealizedPnL_gbpPence_convertsToMajorCurrency() {
        val pnl = InstrumentPriceScale.unrealizedPnL(
            quantity = 168,
            avgPriceRaw = 593.074,
            marketPriceRaw = 592.0,
            currency = "GBP",
            primaryExch = "LSE"
        )
        assertEquals(-1.80432, pnl, absoluteTolerance = 0.0001)
    }

    @Test
    fun unrealizedPnL_gbpWithoutUkExchange_doesNotMagnify() {
        val pnl = InstrumentPriceScale.unrealizedPnL(
            quantity = 1,
            avgPriceRaw = 8_150.0,
            marketPriceRaw = 8_220.0,
            currency = "GBP"
        )
        assertEquals(70.0, pnl, absoluteTolerance = 0.0001)
    }

    @Test
    fun realizedPnLOnClose_gbpPence_convertsToMajorCurrency() {
        val pnl = InstrumentPriceScale.realizedPnLOnClose(
            closeQty = 168,
            avgPriceRaw = 593.074,
            exitPriceRaw = 592.3673,
            currency = "GBP",
            isLong = true,
            primaryExch = "LSE"
        )
        assertEquals(-1.187256, pnl, absoluteTolerance = 0.0001)
    }
}
