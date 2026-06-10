package daytrader.domain

import kotlinx.serialization.Serializable

/**
 * Uniform Touch Turn bootstrap payload from [daytrader.marketdata.MarketDataProvider].
 * Used by the signal engine for ATR liquidity and volume-exhaustion gates.
 */
@Serializable
data class TouchTurnSignalContext(
    val firstCandle: OhlcBar,
    /** 14-period ATR on completed 15-minute bars (prior to the opening bar). */
    val atr14: Double,
    /** Wilder daily ATR(14) on completed daily bars (prior sessions; today excluded). */
    val dailyAtr14: Double? = null,
    /** 20-period SMA of volume on prior session-opening 15m bars (prior to today). */
    val volumeSma20: Double,
    /**
     * True when bootstrap cached ATR/volume only — today's RTH opening 15m bar is not in IB history yet.
     * Start / live session still loads the opening bar after market open.
     */
    val todayOpeningBarPending: Boolean = false
) {
    fun hasBootstrapMetrics(rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT): Boolean {
        val atrOk = !rules.enables.liquidityRange15mAtr || atr14 > 0.0
        val volumeOk = !rules.enables.volumeExhaustion || volumeSma20 > 0.0
        val dailyOk = !rules.enables.liquidityRangeDailyAtr ||
            (dailyAtr14 != null && dailyAtr14 > 0.0)
        return atrOk && volumeOk && dailyOk
    }
}
