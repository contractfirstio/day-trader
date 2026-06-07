package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextualAlignmentEvaluatorTest {

    @Test
    fun bullMacro_lowScore_isBuyTheDip() {
        assertEquals(
            ReversalScoreAlignmentBadge.BUY_THE_DIP,
            ContextualAlignmentEvaluator.badgeLabel(15, MacroTrendState.BULL)
        )
    }

    @Test
    fun bullMacro_highScore_isTrendExhaustion() {
        assertEquals(
            ReversalScoreAlignmentBadge.TREND_EXHAUSTION,
            ContextualAlignmentEvaluator.badgeLabel(85, MacroTrendState.BULL)
        )
    }

    @Test
    fun bearMacro_lowScore_isOversoldBounce() {
        assertEquals(
            ReversalScoreAlignmentBadge.OVERSOLD_BOUNCE,
            ContextualAlignmentEvaluator.badgeLabel(10, MacroTrendState.BEAR)
        )
    }

    @Test
    fun bearMacro_highScore_isSellTheRip() {
        assertEquals(
            ReversalScoreAlignmentBadge.SELL_THE_RIP,
            ContextualAlignmentEvaluator.badgeLabel(92, MacroTrendState.BEAR)
        )
    }

    @Test
    fun midScore_isTrendingRegardlessOfMacro() {
        assertEquals(
            ReversalScoreAlignmentBadge.TRENDING,
            ContextualAlignmentEvaluator.badgeLabel(50, MacroTrendState.BULL)
        )
        assertEquals(
            ReversalScoreAlignmentBadge.TRENDING,
            ContextualAlignmentEvaluator.badgeLabel(50, MacroTrendState.BEAR)
        )
    }

    @Test
    fun nullMacroTrend_isTrending() {
        assertEquals(
            ReversalScoreAlignmentBadge.TRENDING,
            ContextualAlignmentEvaluator.badgeLabel(5, null)
        )
    }

    @Test
    fun spyRegimeSnapshot_evaluatesBullAndBear() {
        val bull = SpyRegimeSnapshot(lastPrice = 510.0, sma200 = 480.0)
        assertEquals(MacroTrendState.BULL, bull.macroTrendState())

        val bear = SpyRegimeSnapshot(lastPrice = 470.0, sma200 = 480.0)
        assertEquals(MacroTrendState.BEAR, bear.macroTrendState())

        val flat = SpyRegimeSnapshot(lastPrice = 480.0, sma200 = 480.0)
        assertEquals(null, flat.macroTrendState())
    }
}
