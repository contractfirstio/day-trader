package daytrader.presentation.strategies

/**
 * Rolling last-trade marks for a live Touch Turn order/position chart.
 * Throttles samples so rapid quote bursts do not flood the series.
 */
class LivePriceTickHistory(
    private val maxPoints: Int = 120,
    private val minIntervalMillis: Long = 500L
) {
    private val points = ArrayDeque<Double>()
    private var lastRecordedAt: Long = 0L

    fun clear() {
        points.clear()
        lastRecordedAt = 0L
    }

    fun record(timestampMillis: Long, price: Double) {
        if (price <= 0.0) return
        if (points.isNotEmpty() && timestampMillis - lastRecordedAt < minIntervalMillis) return
        lastRecordedAt = timestampMillis
        points.addLast(price)
        while (points.size > maxPoints) {
            points.removeFirst()
        }
    }

    fun snapshot(): List<Double> = points.toList()
}
