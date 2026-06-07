package daytrader.domain

/**
 * Translates reversal score metrics and macro regime into human-readable insight copy.
 */
object ReversalScoreInsightGenerator {

    fun enrich(
        base: ReversalScoreResult,
        macroState: MacroTrendState?,
        contextBadge: ReversalScoreAlignmentBadge
    ): ReversalScoreResult =
        base.copy(
            macroState = macroState,
            contextBadge = contextBadge.label,
            insightText = thinking(base.compositeScore, base.priceZScore),
            recommendationText = recommendation(base.compositeScore, macroState)
        )

    fun thinking(compositeScore: Int, priceZScore: Double): String {
        val z = formatZScore(priceZScore)
        return when {
            compositeScore <= 20 ->
                "The asset is statistically exhausted. Price has stretched heavily below its moving average " +
                    "(Z-Score: $z), while panic indicators like Implied Volatility and Volume are spiking. " +
                    "Sellers are capitulating."
            compositeScore in 21..49 ->
                "The asset is bleeding downward, but orderly. Volatility and volume lack the explosive spikes " +
                    "required to signal total seller exhaustion. The rubber band is stretching but not ready to snap."
            compositeScore in 50..79 ->
                "The asset is steadily climbing. Buyers are in control, and price is above its moving average, " +
                    "but we have not yet reached levels of extreme euphoria or overvaluation."
            else ->
                "The asset is stretched to the upside. Price is highly extended (Z-Score: $z) and options premiums " +
                    "suggest complacency. Buyers are exhausted."
        }
    }

    fun recommendation(compositeScore: Int, macroState: MacroTrendState?): String = when {
        compositeScore <= 20 && macroState == MacroTrendState.BULL ->
            "Aggressive Buy. This is a high-probability 'Buy the Dip' setup. You are acquiring a deeply oversold " +
                "asset supported by a strong macro uptrend."
        compositeScore <= 20 && macroState == MacroTrendState.BEAR ->
            "Tactical Buy. This is an 'Oversold Bounce' play. The asset is cheap, but the broader market is falling. " +
                "Expect a sharp, short-term bounce. Keep position sizing small and take profits quickly."
        compositeScore <= 20 ->
            "Tactical Buy. The asset is deeply oversold locally. Confirm the broader macro trend before sizing " +
                "aggressively, and keep risk controls tight."
        compositeScore in 21..79 ->
            "Stand Aside or Hold. The asset is actively trending with no immediate reversal signaled. If you are in " +
                "a winning position, let it ride. Do not initiate new reversal trades here; wait for extreme " +
                "exhaustion (Score < 20 or > 80)."
        compositeScore >= 80 && macroState == MacroTrendState.BULL ->
            "Take Profits. The bull run is locally exhausted. Tighten your stop-losses or trim your position. Do not " +
                "short the asset, as the macro trend is still upward, but protect your gains."
        compositeScore >= 80 && macroState == MacroTrendState.BEAR ->
            "Aggressive Short. This is a classic 'Sell the Rip' setup. A temporary bounce in a bear market has " +
                "exhausted itself. High probability of the downward trend resuming."
        compositeScore >= 80 ->
            "Take Profits. The asset is locally overextended. Trim exposure or tighten stops until macro context " +
                "confirms the next directional leg."
        else -> recommendation(compositeScore, null)
    }

    private fun formatZScore(value: Double): String =
        "%.2f".format(value)
}
