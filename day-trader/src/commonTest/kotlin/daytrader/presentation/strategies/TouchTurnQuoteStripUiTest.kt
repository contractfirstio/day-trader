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
        assertEquals("+12p", TouchTurnQuoteStripFormat.gapLabel(0.12, "GBP"))
        assertEquals("-4p", TouchTurnQuoteStripFormat.gapLabel(-0.04, "GBP"))
        assertEquals("at entry", TouchTurnQuoteStripFormat.gapLabel(0.0, "GBP"))
    }

    @Test
    fun quoteStripUi_isFillableWhenAskCrossesLongEntry() {
        val strip = TouchTurnQuoteStripUi(
            bid = 99.60,
            ask = 99.64,
            last = 99.62,
            currencyCode = "GBP",
            entryPrice = 99.64,
            entrySide = TouchTurnTradeSide.LONG
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
            entrySide = TouchTurnTradeSide.LONG
        )
        assertFalse(strip.isFillable)
        assertEquals("+12p", strip.fillGapLabel)
    }

    @Test
    fun mapper_buildsFromQuoteAndSetup() {
        val bar = OhlcBar(open = 100.24, high = 100.5, low = 99.64, close = 99.74, volume = 1.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        val strip = TouchTurnQuoteStripUiMapper.from(
            quote = LiveQuote(symbol = "LLOY", bid = 99.70, ask = 99.76, last = 99.72),
            currencyCode = "GBP",
            bracketSetup = setup
        )
        assertNotNull(strip)
        assertEquals(99.64, strip.entryPrice)
        assertEquals(TouchTurnTradeSide.LONG, strip.entrySide)
        assertEquals("+12p", strip.fillGapLabel)
    }

    @Test
    fun mapper_includesClosestApproach() {
        val bar = OhlcBar(open = 100.24, high = 100.5, low = 99.64, close = 99.74, volume = 1.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        val strip = TouchTurnQuoteStripUiMapper.from(
            quote = LiveQuote(symbol = "LLOY", bid = 99.70, ask = 99.76, last = 99.72),
            currencyCode = "GBP",
            bracketSetup = setup,
            closestApproach = TouchTurnClosestApproachUi(gap = 0.02, fillPrice = 99.66)
        )
        assertNotNull(strip?.closestApproach)
        assertEquals("+2p", strip?.closestApproach?.gapLabel("GBP"))
        assertEquals(99.66, strip?.closestApproach?.fillPrice)
    }

    @Test
    fun mapper_returnsNullWhenNoQuoteAndNoEntry() {
        assertNull(
            TouchTurnQuoteStripUiMapper.from(
                quote = null,
                currencyCode = "GBP",
                bracketSetup = null
            )
        )
    }
}
