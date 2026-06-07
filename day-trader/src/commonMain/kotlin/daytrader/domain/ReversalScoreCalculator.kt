package daytrader.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure composite reversal score engine.
 *
 * High score (80–100) = overbought / exhaustion (sell bias).
 * Low score (0–20) = oversold / capitulation (buy bias).
 */
object ReversalScoreCalculator {

    private const val WEIGHT_PRICE = 0.25
    private const val WEIGHT_IV_RANK = 0.20
    private const val WEIGHT_RVOL = 0.15
    private const val WEIGHT_HF_MACRO_FEAR = 0.20
    private const val WEIGHT_STRUCTURAL_VIX = 0.10
    private const val WEIGHT_YIELD_CURVE = 0.10

    private const val SMA_WINDOW = 20
    private const val RVOL_AVG_WINDOW = 30

    fun compute(inputs: ReversalScoreInputs): ReversalScoreResult {
        val live = inputs.symbolSnapshot.live
        val historical = inputs.symbolSnapshot.historical
        val closes = historical.dailyCloses.filter { it > 0.0 }
        val volumes = historical.dailyVolumes.filter { it > 0.0 }
        val ivHistory = historical.historicalIvValues.filter { it > 0.0 }

        val sma20 = closes.takeLast(SMA_WINDOW).average().takeIf { it > 0.0 } ?: live.lastPrice
        val priceStd = standardDeviation(closes.takeLast(SMA_WINDOW))
        val priceZ = zScore(live.lastPrice, sma20, priceStd)

        val currentIv = live.impliedVolatility
            ?: ivHistory.lastOrNull()
            ?: estimateIvFromPriceVolatility(closes)
        val ivRank = ivPercentileRank(currentIv, ivHistory)
        val ivRankZ = ivRankAlignedZ(currentIv, ivHistory)

        val avgVolume30 = volumes.takeLast(RVOL_AVG_WINDOW).average().takeIf { it > 0.0 }
            ?: volumes.average().takeIf { it > 0.0 }
            ?: live.volume.coerceAtLeast(1.0)
        val rvol = live.volume / avgVolume30
        val historicalRvol = alignSeries(closes, volumes).map { (_, vol) -> vol / avgVolume30 }
        val rvolZ = invert(zScore(rvol, historicalRvol.average(), standardDeviation(historicalRvol)))

        val macro = inputs.macroVol
        val vixZ = invert(zScore(macro.vix, macro.vixHistory.average(), standardDeviation(macro.vixHistory)))
        val vix1dZ = macro.vix1d?.let { value ->
            invert(zScore(value, macro.vix1dHistory.average(), standardDeviation(macro.vix1dHistory)))
        }
        val vvixZ = macro.vvix?.let { value ->
            invert(zScore(value, macro.vvixHistory.average(), standardDeviation(macro.vvixHistory)))
        }
        val hfMacroFearZ = listOfNotNull(vix1dZ, vvixZ)
            .average()
            .takeIf { !it.isNaN() }
            ?: 0.0

        val yield = inputs.yieldCurve
        val yieldCurveZ = zScore(
            yield.spread,
            yield.spreadHistory.average(),
            standardDeviation(yield.spreadHistory)
        )

        val rawComposite = WEIGHT_PRICE * priceZ +
            WEIGHT_IV_RANK * ivRankZ +
            WEIGHT_RVOL * rvolZ +
            WEIGHT_HF_MACRO_FEAR * hfMacroFearZ +
            WEIGHT_STRUCTURAL_VIX * vixZ +
            WEIGHT_YIELD_CURVE * yieldCurveZ

        val score = normalizeToScore(rawComposite)
        return ReversalScoreResult(
            compositeScore = score,
            rawComposite = rawComposite,
            priceZScore = priceZ,
            rvol = rvol,
            ivRank = ivRank,
            components = ReversalScoreComponents(
                priceZ = priceZ,
                ivRankZ = ivRankZ,
                rvolZ = rvolZ,
                hfMacroFearZ = hfMacroFearZ,
                structuralVixZ = vixZ,
                yieldCurveZ = yieldCurveZ
            )
        )
    }

    /** Percentile rank of current IV within historical window (0–100). */
    fun ivPercentileRank(currentIv: Double, history: List<Double>): Double {
        val values = history.filter { it > 0.0 }
        if (values.isEmpty()) return 50.0
        val rank = values.count { it <= currentIv }.toDouble() / values.size.toDouble()
        return (rank * 100.0).coerceIn(0.0, 100.0)
    }

    /** Inverted z-score of IV Rank vs its expanding-window historical distribution. */
    fun ivRankAlignedZ(currentIv: Double, ivHistory: List<Double>): Double {
        val ivRank = ivPercentileRank(currentIv, ivHistory)
        val rankHistory = historicalIvRankSeries(ivHistory)
        return invert(zScore(ivRank, rankHistory.average(), standardDeviation(rankHistory)))
    }

    /** Expanding-window IV Rank at each historical observation (baseline for IV Rank z-score). */
    fun historicalIvRankSeries(ivHistory: List<Double>): List<Double> =
        ivHistory.indices.mapNotNull { index ->
            val window = ivHistory.take(index + 1).filter { it > 0.0 }
            if (window.size < 2) null else ivPercentileRank(ivHistory[index], window)
        }

    fun normalizeToScore(rawComposite: Double): Int {
        val normalized = ((rawComposite + 3.0) / 6.0) * 100.0
        return min(100, max(0, normalized.toInt()))
    }

    fun zScore(value: Double, mean: Double, stdDev: Double): Double =
        if (stdDev <= 0.0) 0.0 else (value - mean) / stdDev

    fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun invert(z: Double): Double = -z

    private fun estimateIvFromPriceVolatility(closes: List<Double>): Double {
        if (closes.size < 2) return 0.20
        val returns = closes.zipWithNext { prev, next ->
            if (prev <= 0.0) 0.0 else (next - prev) / prev
        }
        return standardDeviation(returns) * sqrt(252.0)
    }

    private fun alignSeries(closes: List<Double>, volumes: List<Double>): List<Pair<Double, Double>> {
        val size = min(closes.size, volumes.size)
        return (0 until size).map { index -> closes[index] to volumes[index] }
    }

    private fun List<Double>.average(): Double = if (isEmpty()) 0.0 else sum() / size
}
