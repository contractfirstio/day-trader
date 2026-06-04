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
    /** 20-period SMA of volume on prior session-opening 15m bars (prior to today). */
    val volumeSma20: Double
)
