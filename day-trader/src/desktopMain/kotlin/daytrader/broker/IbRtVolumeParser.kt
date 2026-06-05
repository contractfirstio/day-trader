package daytrader.broker

/**
 * Parses IB RT Volume generic tick strings (generic tick list "233").
 *
 * Format: `lastPrice;lastSize;lastTime;totalVolume;vwap;singleTradeFlag`
 */
internal object IbRtVolumeParser {
    fun tradeSizeFromRtVolume(value: String): Double? {
        val parts = value.split(';')
        if (parts.size < 2) return null
        val lastSize = parts[1].trim().toDoubleOrNull() ?: return null
        return lastSize.takeIf { it > 0.0 }
    }
}
