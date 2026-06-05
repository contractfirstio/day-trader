package daytrader.gateway

/**
 * Lightweight live quote snapshot for UI display (bid/ask/last).
 *
 * Values are in the same major currency units used by the broker feed (e.g. HKD, USD).
 */
data class LiveQuote(
    val symbol: String,
    val bid: Double? = null,
    val ask: Double? = null,
    val last: Double? = null,
    /** Incremental volume since the previous tick (for buffer-zone monitoring). */
    val tickVolume: Double? = null,
    val quoteEpochMillis: Long = System.currentTimeMillis()
)

