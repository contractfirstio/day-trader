package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReversalScoreCalculatorTest {

    @Test
    fun normalizeToScore_mapsNegativeCompositeToOversoldBand() {
        assertEquals(0, ReversalScoreCalculator.normalizeToScore(-3.0))
        assertEquals(25, ReversalScoreCalculator.normalizeToScore(-1.5))
    }

    @Test
    fun normalizeToScore_mapsPositiveCompositeToOverboughtBand() {
        assertEquals(100, ReversalScoreCalculator.normalizeToScore(3.0))
        assertEquals(75, ReversalScoreCalculator.normalizeToScore(1.5))
    }

    @Test
    fun compute_highPriceAndLowFearProducesHighScore() {
        val closes = List(30) { index -> 100.0 + index * 0.5 }
        val volumes = List(30) { 1_000_000.0 }
        val ivHistory = List(252) { 0.18 + (it % 10) * 0.005 }
        val result = ReversalScoreCalculator.compute(
            ReversalScoreInputs(
                symbol = "SPY",
                symbolSnapshot = ReversalScoreSymbolSnapshot(
                    live = ReversalScoreLiveSnapshot(
                        lastPrice = closes.last() + 5.0,
                        volume = 900_000.0,
                        impliedVolatility = 0.15
                    ),
                    historical = ReversalScoreHistoricalSnapshot(
                        dailyCloses = closes,
                        dailyVolumes = volumes,
                        historicalIvValues = ivHistory
                    )
                ),
                macroVol = ReversalScoreMacroVolSnapshot(
                    vix = 12.0,
                    vix1d = 10.0,
                    vvix = 80.0,
                    vixHistory = List(60) { 14.0 + (it % 5) },
                    vix1dHistory = List(60) { 11.0 + (it % 4) },
                    vvixHistory = List(60) { 85.0 + (it % 6) }
                ),
                yieldCurve = ReversalScoreYieldCurveSnapshot(
                    tenYearYield = 4.5,
                    twoYearYield = 4.0,
                    spread = 0.5,
                    spreadHistory = List(60) { 0.45 + (it % 7) * 0.01 }
                )
            )
        )
        assertTrue(result.score >= 55, "Expected elevated score, got ${result.score}")
    }

    @Test
    fun compute_highFearAndCapitulationVolumeProducesLowScore() {
        val closes = List(30) { index -> 120.0 - index * 0.8 }
        val volumes = List(30) { 800_000.0 }
        val ivHistory = List(252) { 0.20 + (it % 12) * 0.008 }
        val result = ReversalScoreCalculator.compute(
            ReversalScoreInputs(
                symbol = "SPY",
                symbolSnapshot = ReversalScoreSymbolSnapshot(
                    live = ReversalScoreLiveSnapshot(
                        lastPrice = closes.last() - 3.0,
                        volume = 2_500_000.0,
                        impliedVolatility = 0.35
                    ),
                    historical = ReversalScoreHistoricalSnapshot(
                        dailyCloses = closes,
                        dailyVolumes = volumes,
                        historicalIvValues = ivHistory
                    )
                ),
                macroVol = ReversalScoreMacroVolSnapshot(
                    vix = 32.0,
                    vix1d = 40.0,
                    vvix = 140.0,
                    vixHistory = List(60) { 16.0 + (it % 5) },
                    vix1dHistory = List(60) { 18.0 + (it % 4) },
                    vvixHistory = List(60) { 95.0 + (it % 6) }
                ),
                yieldCurve = ReversalScoreYieldCurveSnapshot(
                    tenYearYield = 3.8,
                    twoYearYield = 4.2,
                    spread = -0.4,
                    spreadHistory = List(60) { 0.30 - (it % 7) * 0.02 }
                )
            )
        )
        assertTrue(result.score <= 45, "Expected depressed score, got ${result.score}")
    }

    @Test
    fun ivRankAlignedZ_usesPercentileRankNotRawIv() {
        val ivHistory = List(252) { index -> 0.18 + (index % 12) * 0.008 }
        val lowIv = 0.19
        val highIv = 0.35
        val lowIvRank = ReversalScoreCalculator.ivPercentileRank(lowIv, ivHistory)
        val highIvRank = ReversalScoreCalculator.ivPercentileRank(highIv, ivHistory)
        val lowIvRankZ = ReversalScoreCalculator.ivRankAlignedZ(lowIv, ivHistory)
        val highIvRankZ = ReversalScoreCalculator.ivRankAlignedZ(highIv, ivHistory)
        val rawIvLowZ = invertForTest(
            ReversalScoreCalculator.zScore(
                lowIv,
                ivHistory.average(),
                ReversalScoreCalculator.standardDeviation(ivHistory)
            )
        )
        val rawIvHighZ = invertForTest(
            ReversalScoreCalculator.zScore(
                highIv,
                ivHistory.average(),
                ReversalScoreCalculator.standardDeviation(ivHistory)
            )
        )
        assertTrue(highIvRank > lowIvRank)
        assertTrue(highIvRankZ < lowIvRankZ, "Higher IV Rank should lower aligned z after inversion")
        assertTrue(lowIvRankZ != rawIvLowZ || highIvRankZ != rawIvHighZ, "IV Rank z should differ from raw IV z")
    }

    @Test
    fun compute_highRvolAlwaysInvertedEvenWhenPriceIsExtended() {
        val closes = List(30) { index -> 100.0 + index * 0.5 }
        val volumes = List(30) { index -> 800_000.0 + (index % 5) * 50_000.0 }
        val ivHistory = List(60) { 0.18 + (it % 10) * 0.005 }
        val baseline = ReversalScoreCalculator.compute(
            baselineInputs(closes, volumes, ivHistory, volume = 900_000.0)
        )
        val highRvol = ReversalScoreCalculator.compute(
            baselineInputs(closes, volumes, ivHistory, volume = 3_000_000.0)
        )
        assertTrue(
            highRvol.components.rvolZ < baseline.components.rvolZ,
            "High RVOL should produce a more negative inverted z, baseline=${baseline.components.rvolZ} highRvol=${highRvol.components.rvolZ}"
        )
        assertTrue(
            highRvol.score <= baseline.score,
            "High RVOL should not raise the composite score, baseline=${baseline.score} highRvol=${highRvol.score}"
        )
    }

    @Test
    fun compute_hfMacroFearDoesNotFallBackToStructuralVix() {
        val closes = List(30) { 100.0 + it * 0.1 }
        val volumes = List(30) { 1_000_000.0 }
        val ivHistory = List(60) { 0.18 }
        val withHf = ReversalScoreCalculator.compute(
            ReversalScoreInputs(
                symbol = "SPY",
                symbolSnapshot = ReversalScoreSymbolSnapshot(
                    live = ReversalScoreLiveSnapshot(lastPrice = closes.last(), volume = 1_000_000.0, impliedVolatility = 0.18),
                    historical = ReversalScoreHistoricalSnapshot(closes, volumes, ivHistory)
                ),
                macroVol = ReversalScoreMacroVolSnapshot(
                    vix = 30.0,
                    vix1d = 35.0,
                    vvix = 120.0,
                    vixHistory = List(60) { 16.0 + (it % 5) },
                    vix1dHistory = List(60) { 18.0 + (it % 4) },
                    vvixHistory = List(60) { 95.0 + (it % 6) }
                ),
                yieldCurve = ReversalScoreYieldCurveSnapshot(4.0, 3.8, 0.2, List(60) { 0.2 })
            )
        )
        val withoutHf = ReversalScoreCalculator.compute(
            ReversalScoreInputs(
                symbol = "SPY",
                symbolSnapshot = ReversalScoreSymbolSnapshot(
                    live = ReversalScoreLiveSnapshot(lastPrice = closes.last(), volume = 1_000_000.0, impliedVolatility = 0.18),
                    historical = ReversalScoreHistoricalSnapshot(closes, volumes, ivHistory)
                ),
                macroVol = ReversalScoreMacroVolSnapshot(
                    vix = 30.0,
                    vix1d = null,
                    vvix = null,
                    vixHistory = List(60) { 16.0 + (it % 5) },
                    vix1dHistory = emptyList(),
                    vvixHistory = emptyList()
                ),
                yieldCurve = ReversalScoreYieldCurveSnapshot(4.0, 3.8, 0.2, List(60) { 0.2 })
            )
        )
        assertEquals(0.0, withoutHf.components.hfMacroFearZ)
        assertTrue(withHf.components.hfMacroFearZ < 0.0)
        assertTrue(withHf.components.hfMacroFearZ < withoutHf.components.hfMacroFearZ)
    }

    private fun invertForTest(z: Double): Double = -z

    private fun List<Double>.average(): Double = if (isEmpty()) 0.0 else sum() / size

    private fun baselineInputs(
        closes: List<Double>,
        volumes: List<Double>,
        ivHistory: List<Double>,
        volume: Double
    ): ReversalScoreInputs = ReversalScoreInputs(
        symbol = "SPY",
        symbolSnapshot = ReversalScoreSymbolSnapshot(
            live = ReversalScoreLiveSnapshot(
                lastPrice = closes.last() + 1.0,
                volume = volume,
                impliedVolatility = 0.18
            ),
            historical = ReversalScoreHistoricalSnapshot(
                dailyCloses = closes,
                dailyVolumes = volumes,
                historicalIvValues = ivHistory
            )
        ),
        macroVol = ReversalScoreMacroVolSnapshot(
            vix = 14.0,
            vix1d = 12.0,
            vvix = 85.0,
            vixHistory = List(60) { 14.0 + (it % 5) },
            vix1dHistory = List(60) { 11.0 + (it % 4) },
            vvixHistory = List(60) { 85.0 + (it % 6) }
        ),
        yieldCurve = ReversalScoreYieldCurveSnapshot(
            tenYearYield = 4.5,
            twoYearYield = 4.0,
            spread = 0.5,
            spreadHistory = List(60) { 0.45 + (it % 7) * 0.01 }
        )
    )
}
