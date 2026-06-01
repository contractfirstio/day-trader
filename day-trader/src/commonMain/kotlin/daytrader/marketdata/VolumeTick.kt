package daytrader.marketdata

/**
 * Incremental volume from a live quote or tick stream for buffer-zone monitoring.
 */
data class VolumeTick(
    val symbol: String,
    val volumeDelta: Double,
    val epochMillis: Long = System.currentTimeMillis()
)
