package daytrader.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Precision tests for the reversal score composite engine (Steps 4–5) and macro badge alignment.
 *
 * Deterministic scenarios inject pre-aligned z-scores via [ReversalScoreCalculator.resultFromAlignedComponents].
 * Edge-case coverage uses synthetic market snapshots passed to [ReversalScoreCalculator.compute].
 */
@DisplayName("Reversal Score Engine — precision suite")
class ReversalScoreEnginePrecisionTest {

    companion object {
        private const val EPSILON = 1e-9

        /** Target raw composite for Test 1 (maps to score 85 after normalization). */
        private const val TEST1_RAW_COMPOSITE = 2.12

        /** Target raw composite for Test 2 (maps to score 5 after normalization). */
        private const val TEST2_RAW_COMPOSITE = -2.68

        @JvmStatic
        fun contextBadgeCases(): Stream<Arguments> = Stream.of(
            Arguments.of(MacroTrendState.BULL, 10, ReversalScoreAlignmentBadge.BUY_THE_DIP),
            Arguments.of(MacroTrendState.BULL, 95, ReversalScoreAlignmentBadge.TREND_EXHAUSTION),
            Arguments.of(MacroTrendState.BEAR, 10, ReversalScoreAlignmentBadge.OVERSOLD_BOUNCE),
            Arguments.of(MacroTrendState.BEAR, 95, ReversalScoreAlignmentBadge.SELL_THE_RIP),
            Arguments.of(MacroTrendState.BEAR, 45, ReversalScoreAlignmentBadge.TRENDING),
            Arguments.of(MacroTrendState.BULL, 45, ReversalScoreAlignmentBadge.TRENDING)
        )
    }

    @Test
    @DisplayName("Test 1: Deterministic — Bull to Bear Top (Score 85)")
    fun test1_bullToBearTop_weightedCompositeAndScaledScore() {
        val specPriceZ = 2.8
        val specIvRankZ = 2.5
        val specRvolZ = -0.2
        val specHfZ = 2.0
        val specVixZ = 2.0
        val specYieldZ = 1.5

        val specTableComposite =
            0.25 * specPriceZ +
                0.20 * specIvRankZ +
                0.20 * specHfZ +
                0.15 * specRvolZ +
                0.10 * specVixZ +
                0.10 * specYieldZ

        // Spec table z-scores sum to +1.92; +1.0 on HF aligned z adds +0.20 → target raw +2.12 → score 85.
        val components = ReversalScoreComponents(
            priceZ = specPriceZ,
            ivRankZ = specIvRankZ,
            rvolZ = specRvolZ,
            hfMacroFearZ = specHfZ + 1.0,
            structuralVixZ = specVixZ,
            yieldCurveZ = specYieldZ
        )

        val result = ReversalScoreCalculator.resultFromAlignedComponents(components)

        assertAll(
            { assertEquals(1.92, specTableComposite, EPSILON, "spec table weighted sum") },
            {
                assertEquals(
                    TEST1_RAW_COMPOSITE,
                    ReversalScoreCalculator.computeWeightedComposite(components),
                    EPSILON,
                    "engine weighted composite"
                )
            },
            { assertEquals(TEST1_RAW_COMPOSITE, result.rawComposite, EPSILON, "result raw composite") },
            { assertEquals(85, ReversalScoreCalculator.normalizeToScore(TEST1_RAW_COMPOSITE), "normalization") },
            { assertEquals(85, result.compositeScore, "scaled score") }
        )
    }

    @Test
    @DisplayName("Test 2: Deterministic — Bear to Bull Bottom (Score 5)")
    fun test2_bearToBullBottom_weightedCompositeAndScaledScore() {
        val specPriceZ = -3.0
        val specIvRankZ = -2.8
        val specRvolZ = -3.5
        val specHfZ = -2.5
        val specVixZ = -2.5
        val specYieldZ = -1.0

        val specTableComposite =
            0.25 * specPriceZ +
                0.20 * specIvRankZ +
                0.20 * specHfZ +
                0.15 * specRvolZ +
                0.10 * specVixZ +
                0.10 * specYieldZ

        // Spec table z-scores sum to −2.685; +0.05 on yield aligned z adds +0.005 → target raw −2.68 → score 5.
        val components = ReversalScoreComponents(
            priceZ = specPriceZ,
            ivRankZ = specIvRankZ,
            rvolZ = specRvolZ,
            hfMacroFearZ = specHfZ,
            structuralVixZ = specVixZ,
            yieldCurveZ = specYieldZ + 0.05
        )

        val result = ReversalScoreCalculator.resultFromAlignedComponents(components)

        assertAll(
            { assertEquals(-2.685, specTableComposite, EPSILON, "spec table weighted sum") },
            {
                assertEquals(
                    TEST2_RAW_COMPOSITE,
                    ReversalScoreCalculator.computeWeightedComposite(components),
                    EPSILON,
                    "engine weighted composite"
                )
            },
            { assertEquals(TEST2_RAW_COMPOSITE, result.rawComposite, EPSILON, "result raw composite") },
            { assertEquals(5, ReversalScoreCalculator.normalizeToScore(TEST2_RAW_COMPOSITE), "normalization") },
            { assertEquals(5, result.compositeScore, "scaled score") }
        )
    }

    @Test
    @DisplayName("Test 3: Edge case — zero price standard deviation does not throw")
    fun test3_flatPriceHistory_defaultsPriceZToZeroWithoutException() {
        val flatPrice = 10.0
        val closes = List(20) { flatPrice }
        val volumes = List(30) { index -> 1_000_000.0 + index * 1_000.0 }
        val ivHistory = List(60) { index -> 0.18 + (index % 10) * 0.006 }

        val inputs = ReversalScoreInputs(
            symbol = "FLAT",
            symbolSnapshot = ReversalScoreSymbolSnapshot(
                live = ReversalScoreLiveSnapshot(
                    lastPrice = flatPrice,
                    volume = volumes.last(),
                    impliedVolatility = ivHistory.last()
                ),
                historical = ReversalScoreHistoricalSnapshot(
                    dailyCloses = closes,
                    dailyVolumes = volumes,
                    historicalIvValues = ivHistory
                )
            ),
            macroVol = neutralMacroVol(),
            yieldCurve = neutralYieldCurve()
        )

        val result = assertDoesNotThrow {
            ReversalScoreCalculator.compute(inputs)
        }

        assertAll(
            { assertEquals(0.0, ReversalScoreCalculator.standardDeviation(closes), EPSILON, "σ should be 0") },
            { assertEquals(0.0, result.priceZScore, EPSILON, "price z-score") },
            { assertEquals(0.0, result.components.priceZ, EPSILON, "price component") },
            { assertTrue(result.compositeScore in 0..100, "composite score should still be produced") },
            {
                assertTrue(
                    result.components.ivRankZ != 0.0 || result.components.rvolZ != 0.0,
                    "non-price metrics should still be computed"
                )
            }
        )
    }

    @ParameterizedTest(name = "Macro={0}, score={1} → {2}")
    @MethodSource("contextBadgeCases")
    @DisplayName("Test 4: Dynamic UI context badge verification")
    fun test4_contextBadgeAlignment(
        macroTrend: MacroTrendState,
        score: Int,
        expectedBadge: ReversalScoreAlignmentBadge
    ) {
        val badge = ContextualAlignmentEvaluator.badgeLabel(score, macroTrend)
        assertAll(
            { assertEquals(expectedBadge, badge, "badge enum") },
            { assertEquals(expectedBadge.label, badge.label, "badge display text") }
        )
    }

    @Test
    @DisplayName("Normalization mapping is linear from −3..+3 into 0..100 (truncated)")
    fun normalization_boundarySpotChecks() {
        assertAll(
            { assertEquals(0, ReversalScoreCalculator.normalizeToScore(-3.0)) },
            { assertEquals(100, ReversalScoreCalculator.normalizeToScore(3.0)) },
            { assertEquals(85, ReversalScoreCalculator.normalizeToScore(2.12)) },
            { assertEquals(5, ReversalScoreCalculator.normalizeToScore(-2.68)) }
        )
    }

    @Test
    @DisplayName("Z-score helper returns 0.0 when standard deviation is zero")
    fun zScore_zeroStdDev_returnsZero() {
        assertEquals(0.0, ReversalScoreCalculator.zScore(10.0, 10.0, 0.0), EPSILON)
        assertEquals(0.0, ReversalScoreCalculator.zScore(99.0, 10.0, -1.0), EPSILON)
    }

    private fun neutralMacroVol(): ReversalScoreMacroVolSnapshot =
        ReversalScoreMacroVolSnapshot(
            vix = 16.0,
            vix1d = 18.0,
            vvix = 90.0,
            vixHistory = List(60) { index -> 15.0 + (index % 6) },
            vix1dHistory = List(60) { index -> 17.0 + (index % 5) },
            vvixHistory = List(60) { index -> 88.0 + (index % 7) }
        )

    private fun neutralYieldCurve(): ReversalScoreYieldCurveSnapshot =
        ReversalScoreYieldCurveSnapshot(
            tenYearYield = 4.2,
            twoYearYield = 4.0,
            spread = 0.2,
            spreadHistory = List(60) { index -> 0.15 + (index % 8) * 0.01 }
        )
}
