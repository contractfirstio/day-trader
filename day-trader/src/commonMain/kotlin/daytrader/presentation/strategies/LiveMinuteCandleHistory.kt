package daytrader.presentation.strategies

import daytrader.domain.OhlcBar

/**
 * Builds rolling one-minute OHLC candles from live mark prices.
 * Keeps up to [maxCandles] completed bars plus the in-progress bar for the current minute.
 */
class LiveMinuteCandleHistory(
    private val intervalMillis: Long = 60_000L,
    private val maxCandles: Int = 60
) {
    private val completed = ArrayDeque<MinuteCandle>()
    private var forming: MinuteCandle? = null

    fun clear() {
        completed.clear()
        forming = null
    }

    fun record(timestampMillis: Long, price: Double) {
        if (price <= 0.0 || timestampMillis < 0L) return
        val bucketStart = bucketStartFor(timestampMillis)
        val current = forming
        when {
            current == null -> forming = MinuteCandle.open(bucketStart, price)
            bucketStart == current.bucketStartMillis -> forming = current.update(price)
            bucketStart > current.bucketStartMillis -> {
                completed.addLast(current)
                trimCompleted()
                forming = MinuteCandle.open(bucketStart, price)
            }
        }
    }

    fun snapshot(): List<MinuteCandle> {
        val bars = completed.toMutableList()
        forming?.let { bars.add(it) }
        return bars
    }

    private fun trimCompleted() {
        while (completed.size > maxCandles) {
            completed.removeFirst()
        }
    }

    private fun bucketStartFor(timestampMillis: Long): Long =
        (timestampMillis / intervalMillis) * intervalMillis
}

data class MinuteCandle(
    val bucketStartMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
) {
    fun update(price: Double): MinuteCandle = copy(
        high = maxOf(high, price),
        low = minOf(low, price),
        close = price
    )

    fun toOhlcBar(): OhlcBar = OhlcBar(
        open = open,
        high = high,
        low = low,
        close = close
    )

    companion object {
        fun open(bucketStartMillis: Long, price: Double): MinuteCandle =
            MinuteCandle(
                bucketStartMillis = bucketStartMillis,
                open = price,
                high = price,
                low = price,
                close = price
            )
    }
}
