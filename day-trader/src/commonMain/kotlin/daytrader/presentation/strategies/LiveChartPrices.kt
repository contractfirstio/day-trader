package daytrader.presentation.strategies

/** Sanitizes live quote marks before chart range math and canvas drawing. */
object LiveChartPrices {
    fun sanitize(values: Iterable<Double>): List<Double> =
        values.mapNotNull { value ->
            value.takeIf { it.isFinite() && it > 0.0 }
        }
}
