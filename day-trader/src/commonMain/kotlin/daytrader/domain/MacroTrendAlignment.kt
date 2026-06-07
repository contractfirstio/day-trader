package daytrader.domain

/** Broad S&P 500 (SPY) macro trend relative to the 200-day SMA. */
enum class MacroTrendState {
    BULL,
    BEAR
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
