package daytrader.presentation.strategies

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnQuoteStripUiTest {
    @Test
    fun fillGap_long_usesAskMinusEntry() {
        val gap = TouchTurnQuoteStripFormat.fillGap(
            entryPrice = 99.64,
            entrySide = TouchTurnTradeSide.LONG,
            bid = 99.70,
            ask = 99.76
        )
        assertEquals(0.12, gap!!, absoluteTolerance = 0.0001)
    }

    @Test
    fun fillGap_short_usesEntryMinusBid() {
        val gap = TouchTurnQuoteStripFormat.fillGap(
            entryPrice = 100.0,
            entrySide = TouchTurnTradeSide.SHORT,
            bid = 99.88,
            ask = 99.92
        )
        assertEquals(0.12, gap!!, absoluteTolerance = 0.0001)
    }

    @Test
    fun gapLabel_gbp_formatsAsPence() {
        assertEquals("+12p", TouchTurnQuoteStripFormat.gapLabel(12.0, "GBP", "LSE"))
        assertEquals("-4p", TouchTurnQuoteStripFormat.gapLabel(-4.0, "GBP", "LSE"))
        assertEquals("at entry", TouchTurnQuoteStripFormat.gapLabel(0.0, "GBP", "LSE"))
        assertEquals("+0p", TouchTurnQuoteStripFormat.gapLabel(0.12, "GBP", "LSE"))
    }

    @Test
    fun quoteStripUi_isFillableWhenAskCrossesLongEntry() {
        val strip = TouchTurnQuoteStripUi(
            bid = 99.60,
            ask = 99.64,
            last = 99.62,
            currencyCode = "GBP",
            entryPrice = 99.64,
            entrySide = TouchTurnTradeSide.LONG,
            listingExch = "LSE"
        )
        assertTrue(strip.isFillable)
        assertEquals("at entry", strip.fillGapLabel)
    }

    @Test
    fun quoteStripUi_isNotFillableWhenAskAboveLongEntry() {
        val strip = TouchTurnQuoteStripUi(
            bid = 99.70,
            ask = 99.76,
            last = 99.72,
            currencyCode = "GBP",
            entryPrice = 99.64,
            entrySide = TouchTurnTradeSide.LONG,
            listingExch = "LSE"
        )
        assertFalse(strip.isFillable)
        assertEquals("+0p", strip.fillGapLabel)
    }

    @Test
    fun mapper_buildsFromQuoteAndSetup() {
        val bar = OhlcBar(open = 100.24, high = 100.5, low = 99.64, close = 99.74, volume = 1.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        val strip = TouchTurnQuoteStripUiMapper.from(
            quote = LiveQuote(symbol = "LLOY", bid = 99.70, ask = 99.76, last = 99.72),
            currencyCode = "GBP",
            bracketSetup = setup,
            listingExch = "LSE"
        )
        assertNotNull(strip)
        val resolved = strip!!
        assertEquals(setup.entry, resolved.entryPrice!!, absoluteTolerance = 0.0001)
        assertEquals(TouchTurnTradeSide.LONG, resolved.entrySide)
        assertEquals(
            TouchTurnQuoteStripFormat.gapLabel(99.76 - setup.entry, "GBP", "LSE"),
            resolved.fillGapLabel
        )
    }

    @Test
    fun mapper_includesClosestApproach() {
        val bar = OhlcBar(open = 100.24, high = 100.5, low = 99.64, close = 99.74, volume = 1.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        val strip = TouchTurnQuoteStripUiMapper.from(
            quote = LiveQuote(symbol = "LLOY", bid = 99.70, ask = 99.76, last = 99.72),
            currencyCode = "GBP",
            bracketSetup = setup,
            closestApproach = TouchTurnClosestApproachUi(gap = 2.0, fillPrice = 99.66),
            listingExch = "LSE"
        )
        assertNotNull(strip?.closestApproach)
        assertEquals("+2p", strip?.closestApproach?.gapLabel("GBP", "LSE"))
        assertEquals(99.66, strip?.closestApproach?.fillPrice)
    }

    @Test
    fun chartPrice_shortInPositionUsesAskEvenWhenLastBelowTp() {
        val price = TouchTurnQuoteStripUiMapper.chartPrice(
            entrySide = TouchTurnTradeSide.SHORT,
            bid = 109.6,
            ask = 109.7,
            inPosition = true
        )
        assertEquals(109.7, price)
    }

    @Test
    fun chartPrice_shortAwaitingEntryUsesBid() {
        val price = TouchTurnQuoteStripUiMapper.chartPrice(
            entrySide = TouchTurnTradeSide.SHORT,
            bid = 109.6,
            ask = 109.7,
            inPosition = false
        )
        assertEquals(109.6, price)
    }
}
