package daytrader.domain

import kotlinx.serialization.Serializable

/**
 * Uniform Touch Turn bootstrap payload from [daytrader.marketdata.MarketDataProvider].
 * Used by the signal engine for daily ATR liquidity gates.
 */
@Serializable
data class TouchTurnSignalContext(
    val firstCandle: OhlcBar,
    /** 14-period ATR on completed 15-minute bars (prior to the opening bar). */
    val atr14: Double,
    /** Wilder daily ATR(14) on completed daily bars (prior sessions; today excluded). */
    val dailyAtr14: Double? = null,
    /** Legacy field — volume gates removed; always 0. */
    val volumeSma20: Double = 0.0,
    /**
     * True when bootstrap cached metrics only — today's RTH opening 15m bar is not in IB history yet.
     * Start / live session still loads the opening bar after market open.
     */
    val todayOpeningBarPending: Boolean = false
) {
    fun hasBootstrapMetrics(rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT): Boolean =
        !rules.enables.liquidityRangeDailyAtr || (dailyAtr14 != null && dailyAtr14 > 0.0)
}
