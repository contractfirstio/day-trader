package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReversalScoreInsightGeneratorTest {

    @Test
    fun thinking_oversoldBand_mentionsZScore() {
        val text = ReversalScoreInsightGenerator.thinking(compositeScore = 15, priceZScore = -2.35)
        assertTrue(text.contains("Z-Score: -2.35"))
        assertTrue(text.contains("statistically exhausted"))
    }

    @Test
    fun thinking_midBand_isOrderlyBleed() {
        val text = ReversalScoreInsightGenerator.thinking(compositeScore = 35, priceZScore = -0.5)
        assertTrue(text.contains("bleeding downward"))
    }

    @Test
    fun thinking_overboughtBand_mentionsComplacency() {
        val text = ReversalScoreInsightGenerator.thinking(compositeScore = 88, priceZScore = 2.1)
        assertTrue(text.contains("Z-Score: 2.10"))
        assertTrue(text.contains("complacency"))
    }

    @Test
    fun recommendation_bullOversold_isAggressiveBuy() {
        val text = ReversalScoreInsightGenerator.recommendation(10, MacroTrendState.BULL)
        assertTrue(text.startsWith("Aggressive Buy"))
    }

    @Test
    fun recommendation_bearOversold_isTacticalBuy() {
        val text = ReversalScoreInsightGenerator.recommendation(10, MacroTrendState.BEAR)
        assertTrue(text.startsWith("Tactical Buy"))
    }

    @Test
    fun recommendation_trendingBand_isStandAside() {
        val text = ReversalScoreInsightGenerator.recommendation(50, MacroTrendState.BULL)
        assertTrue(text.startsWith("Stand Aside or Hold"))
    }

    @Test
    fun recommendation_bullOverbought_isTakeProfits() {
        val text = ReversalScoreInsightGenerator.recommendation(90, MacroTrendState.BULL)
        assertTrue(text.startsWith("Take Profits"))
    }

    @Test
    fun recommendation_bearOverbought_isAggressiveShort() {
        val text = ReversalScoreInsightGenerator.recommendation(90, MacroTrendState.BEAR)
        assertTrue(text.startsWith("Aggressive Short"))
    }

    @Test
    fun enrich_populatesInsightFields() {
        val base = ReversalScoreResult(
            compositeScore = 12,
            rawComposite = -2.0,
            priceZScore = -2.1,
            rvol = 2.4,
            ivRank = 88.0,
            components = ReversalScoreComponents(
                priceZ = -2.1,
                ivRankZ = 1.2,
                rvolZ = -1.0,
                hfMacroFearZ = 1.5,
                structuralVixZ = 1.0,
                yieldCurveZ = 0.2
            )
        )
        val enriched = ReversalScoreInsightGenerator.enrich(
            base = base,
            macroState = MacroTrendState.BULL,
            contextBadge = ReversalScoreAlignmentBadge.BUY_THE_DIP
        )
        assertEquals(MacroTrendState.BULL, enriched.macroState)
        assertEquals("BUY THE DIP", enriched.contextBadge)
        assertTrue(enriched.insightText.isNotBlank())
        assertTrue(enriched.recommendationText.isNotBlank())
    }
}
