package daytrader.domain

/**
 * Live symbol ticks from IB reqMktData (last, volume, option implied vol).
 */
data class ReversalScoreLiveSnapshot(
    val lastPrice: Double,
    val volume: Double,
    val impliedVolatility: Double?
)

/**
 * Historical symbol series for SMA, RVOL, and IV rank windows.
 */
data class ReversalScoreHistoricalSnapshot(
    val dailyCloses: List<Double>,
    val dailyVolumes: List<Double>,
    val historicalIvValues: List<Double>
)

data class ReversalScoreSymbolSnapshot(
    val live: ReversalScoreLiveSnapshot,
    val historical: ReversalScoreHistoricalSnapshot
)

/**
 * Macro volatility indices (VIX, VIX1D, VVIX) with history for z-score baselines.
 */
data class ReversalScoreMacroVolSnapshot(
    val vix: Double,
    val vix1d: Double?,
    val vvix: Double?,
    val vixHistory: List<Double>,
    val vix1dHistory: List<Double>,
    val vvixHistory: List<Double>
)

/**
 * 10Y–2Y Treasury spread from FRED with history for z-score baselines.
 */
data class ReversalScoreYieldCurveSnapshot(
    val tenYearYield: Double,
    val twoYearYield: Double,
    val spread: Double,
    val spreadHistory: List<Double>
)

data class ReversalScoreInputs(
    val symbol: String,
    val symbolSnapshot: ReversalScoreSymbolSnapshot,
    val macroVol: ReversalScoreMacroVolSnapshot,
    val yieldCurve: ReversalScoreYieldCurveSnapshot
)

data class ReversalScoreComponents(
    val priceZ: Double,
    /** Inverted z-score of IV Rank percentile (0–100), not raw IV. */
    val ivRankZ: Double,
    /** Inverted z-score of relative volume. */
    val rvolZ: Double,
    val hfMacroFearZ: Double,
    val structuralVixZ: Double,
    val yieldCurveZ: Double
)

data class ReversalScoreResult(
    /** Normalized 0–100 score for UI display. */
    val compositeScore: Int,
    /** Raw weighted composite before normalization. */
    val rawComposite: Double,
    /** SPY 200-SMA macro trend at calculation time. */
    val macroState: MacroTrendState? = null,
    /** Context badge label (e.g. BUY THE DIP). */
    val contextBadge: String = "",
    val priceZScore: Double,
    /** Relative volume vs 30-day average (1.0 = average). */
    val rvol: Double,
    /** Implied volatility percentile rank 0–100 within history. */
    val ivRank: Double,
    /** Generated "Thinking" copy. */
    val insightText: String = "",
    /** Generated "Recommendation" copy. */
    val recommendationText: String = "",
    val components: ReversalScoreComponents
) {
    /** @deprecated use [compositeScore] */
    val score: Int get() = compositeScore
}
