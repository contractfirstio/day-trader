package daytrader.domain

import kotlinx.serialization.Serializable

/** Broad S&P 500 (SPY) macro trend relative to the 200-day SMA. */
@Serializable
enum class MacroTrendState {
    BULL,
    BEAR
}

/** Home-market index used for Touch Turn macro trend alignment. */
@Serializable
data class MacroBenchmark(
    val symbol: String,
    val label: String,
    val marketZoneId: String
)

object HomeMarketMacroBenchmark {
    fun forMarketZoneId(marketZoneId: String): MacroBenchmark = when (marketZoneId) {
        RthMarketSessions.HK.zoneId ->
            MacroBenchmark("HSI", "Hang Seng", RthMarketSessions.HK.zoneId)
        RthMarketSessions.EUR.zoneId, "Europe/Berlin" ->
            MacroBenchmark("UKX", "FTSE 100", RthMarketSessions.EUR.zoneId)
        else ->
            MacroBenchmark("SPY", "S&P 500", RthMarketSessions.US.zoneId)
    }
}

/** Home-market index level vs 200-day SMA for Touch Turn macro alignment. */
data class MacroRegimeSnapshot(
    val benchmark: MacroBenchmark,
    val lastPrice: Double,
    val sma200: Double,
    val dailyCloses: List<Double> = emptyList()
) {
    fun macroTrendState(): MacroTrendState? = when {
        lastPrice > sma200 -> MacroTrendState.BULL
        lastPrice < sma200 -> MacroTrendState.BEAR
        else -> null
    }
}

object MacroRegimeEvaluator {
    const val SMA_WINDOW = SpyRegimeEvaluator.SMA_WINDOW

    fun sma200(dailyCloses: List<Double>): Double? = SpyRegimeEvaluator.sma200(dailyCloses)

    fun buildSnapshot(
        benchmark: MacroBenchmark,
        lastPrice: Double,
        dailyCloses: List<Double>
    ): Result<MacroRegimeSnapshot> {
        if (lastPrice <= 0.0) {
            return Result.failure(IllegalStateException("${benchmark.label} last price unavailable"))
        }
        val sma = sma200(dailyCloses)
            ?: return Result.failure(
                IllegalStateException(
                    "Need at least $SMA_WINDOW daily closes for ${benchmark.label} 200-SMA " +
                        "(got ${dailyCloses.size})"
                )
            )
        return Result.success(
            MacroRegimeSnapshot(
                benchmark = benchmark,
                lastPrice = lastPrice,
                sma200 = sma,
                dailyCloses = dailyCloses.filter { it > 0.0 }
            )
        )
    }

    fun buildSyntheticSnapshot(
        benchmark: MacroBenchmark,
        lastPrice: Double,
        trend: MacroTrendState
    ): MacroRegimeSnapshot {
        val closes = syntheticDailyCloses(lastPrice, trend, SMA_WINDOW)
        return MacroRegimeSnapshot(
            benchmark = benchmark,
            lastPrice = lastPrice,
            sma200 = closes.takeLast(SMA_WINDOW).average(),
            dailyCloses = closes
        )
    }

    internal fun syntheticDailyCloses(
        lastPrice: Double,
        trend: MacroTrendState,
        count: Int
    ): List<Double> = when (trend) {
        MacroTrendState.BULL -> List(count) { index ->
            lastPrice * (1.0 - (count - index) * 0.0004)
        }
        MacroTrendState.BEAR -> List(count) { index ->
            lastPrice * (1.0 + (count - index) * 0.0004)
        }
    }
}

enum class ReversalScoreAlignmentBadge(val label: String) {
    BUY_THE_DIP("BUY THE DIP"),
    TREND_EXHAUSTION("TREND EXHAUSTION"),
    OVERSOLD_BOUNCE("OVERSOLD BOUNCE"),
    SELL_THE_RIP("SELL THE RIP"),
    TRENDING("TRENDING")
}

data class SpyRegimeSnapshot(
    val lastPrice: Double,
    val sma200: Double,
    val dailyCloses: List<Double> = emptyList()
) {
    fun macroTrendState(): MacroTrendState? = when {
        lastPrice > sma200 -> MacroTrendState.BULL
        lastPrice < sma200 -> MacroTrendState.BEAR
        else -> null
    }
}

object SpyRegimeEvaluator {
    const val SMA_WINDOW = 200

    fun sma200(dailyCloses: List<Double>): Double? {
        val closes = dailyCloses.filter { it > 0.0 }
        if (closes.size < SMA_WINDOW) return null
        return closes.takeLast(SMA_WINDOW).average()
    }

    fun buildSnapshot(lastPrice: Double, dailyCloses: List<Double>): Result<SpyRegimeSnapshot> {
        if (lastPrice <= 0.0) {
            return Result.failure(IllegalStateException("SPY last price unavailable"))
        }
        val sma = sma200(dailyCloses)
            ?: return Result.failure(
                IllegalStateException("Need at least $SMA_WINDOW daily closes for SPY 200-SMA (got ${dailyCloses.size})")
            )
        return Result.success(
            SpyRegimeSnapshot(
                lastPrice = lastPrice,
                sma200 = sma,
                dailyCloses = dailyCloses.filter { it > 0.0 }
            )
        )
    }
}

/** Symbol trend relative to the 20-day SMA (aligned with reversal-score price window). */
@Serializable
enum class StockTrendState {
    UP,
    DOWN
}

data class StockTrendSnapshot(
    val lastPrice: Double,
    val sma20: Double,
    val dailyCloses: List<Double> = emptyList()
) {
    fun stockTrendState(): StockTrendState? = when {
        lastPrice > sma20 -> StockTrendState.UP
        lastPrice < sma20 -> StockTrendState.DOWN
        else -> null
    }
}

object StockTrendEvaluator {
    const val SMA_WINDOW = 20

    fun sma20(dailyCloses: List<Double>): Double? {
        val closes = dailyCloses.filter { it > 0.0 }
        if (closes.size < SMA_WINDOW) return null
        return closes.takeLast(SMA_WINDOW).average()
    }

    fun buildSyntheticSnapshot(lastPrice: Double, trend: StockTrendState): StockTrendSnapshot {
        val closes = when (trend) {
            StockTrendState.UP -> MacroRegimeEvaluator.syntheticDailyCloses(lastPrice, MacroTrendState.BULL, SMA_WINDOW)
            StockTrendState.DOWN -> MacroRegimeEvaluator.syntheticDailyCloses(lastPrice, MacroTrendState.BEAR, SMA_WINDOW)
        }
        return StockTrendSnapshot(
            lastPrice = lastPrice,
            sma20 = closes.takeLast(SMA_WINDOW).average(),
            dailyCloses = closes
        )
    }

    fun paddedDailyCloses(
        lastPrice: Double,
        dailyCloses: List<Double>,
        trend: StockTrendState,
        minCount: Int = SMA_WINDOW
    ): List<Double> = historicalClosesForTrend(lastPrice, trend, minCount, dailyCloses)

    fun historicalClosesForTrend(
        lastPrice: Double,
        trend: StockTrendState,
        minCount: Int = SMA_WINDOW,
        observedCloses: List<Double> = emptyList()
    ): List<Double> {
        if (observedCloses.size >= minCount) return observedCloses
        val macroTrend = when (trend) {
            StockTrendState.UP -> MacroTrendState.BULL
            StockTrendState.DOWN -> MacroTrendState.BEAR
        }
        val synthetic = MacroRegimeEvaluator.syntheticDailyCloses(lastPrice, macroTrend, minCount)
        if (observedCloses.isEmpty()) return synthetic
        val prefixCount = (minCount - observedCloses.size).coerceAtLeast(0)
        return synthetic.take(prefixCount) + observedCloses
    }

    fun buildSnapshot(lastPrice: Double, dailyCloses: List<Double>): Result<StockTrendSnapshot> {
        if (lastPrice <= 0.0) {
            return Result.failure(IllegalStateException("Symbol last price unavailable"))
        }
        val sma = sma20(dailyCloses)
            ?: return Result.failure(
                IllegalStateException("Need at least $SMA_WINDOW daily closes for 20-SMA (got ${dailyCloses.size})")
            )
        return Result.success(
            StockTrendSnapshot(
                lastPrice = lastPrice,
                sma20 = sma,
                dailyCloses = dailyCloses.filter { it > 0.0 }
            )
        )
    }
}

/**
 * Touch Turn fade alignment: green short only in bear/down tape, red long only in bull/up tape.
 * Does not implement continuation entries (e.g. bear + red short).
 */
object TouchTurnTrendAlignment {
    fun requiredMacroTrend(setup: TouchTurnBracketSetup): MacroTrendState? = when (setup.candleColor) {
        FirstCandleColor.GREEN -> MacroTrendState.BEAR
        FirstCandleColor.RED -> MacroTrendState.BULL
        FirstCandleColor.DOJI -> null
    }

    fun requiredStockTrend(setup: TouchTurnBracketSetup): StockTrendState? = when (setup.candleColor) {
        FirstCandleColor.GREEN -> StockTrendState.DOWN
        FirstCandleColor.RED -> StockTrendState.UP
        FirstCandleColor.DOJI -> null
    }

    fun macroTrendAligned(setup: TouchTurnBracketSetup, actual: MacroTrendState?): Boolean {
        val required = requiredMacroTrend(setup) ?: return true
        return actual == required
    }

    fun stockTrendAligned(setup: TouchTurnBracketSetup, actual: StockTrendState?): Boolean {
        val required = requiredStockTrend(setup) ?: return true
        return actual == required
    }
}

object ContextualAlignmentEvaluator {
    fun badgeLabel(score: Int, macroTrend: MacroTrendState?): ReversalScoreAlignmentBadge {
        if (macroTrend == null || score in 21..79) {
            return ReversalScoreAlignmentBadge.TRENDING
        }
        return when (macroTrend) {
            MacroTrendState.BULL -> when {
                score <= 20 -> ReversalScoreAlignmentBadge.BUY_THE_DIP
                score >= 80 -> ReversalScoreAlignmentBadge.TREND_EXHAUSTION
                else -> ReversalScoreAlignmentBadge.TRENDING
            }
            MacroTrendState.BEAR -> when {
                score <= 20 -> ReversalScoreAlignmentBadge.OVERSOLD_BOUNCE
                score >= 80 -> ReversalScoreAlignmentBadge.SELL_THE_RIP
                else -> ReversalScoreAlignmentBadge.TRENDING
            }
        }
    }
}
