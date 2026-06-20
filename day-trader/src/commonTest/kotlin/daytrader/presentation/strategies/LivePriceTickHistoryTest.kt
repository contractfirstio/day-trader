package daytrader.presentation.strategies

import kotlin.test.Test
import kotlin.test.assertEquals

class LivePriceTickHistoryTest {

    @Test
    fun record_appendsPricesInOrder() {
        val history = LivePriceTickHistory(minIntervalMillis = 0L)
        history.record(0L, 100.0)
        history.record(1L, 101.0)
        history.record(2L, 99.5)
        assertEquals(listOf(100.0, 101.0, 99.5), history.snapshot())
    }

    @Test
    fun record_trimsToMaxPoints() {
        val history = LivePriceTickHistory(maxPoints = 2, minIntervalMillis = 0L)
        history.record(0L, 1.0)
        history.record(1L, 2.0)
        history.record(2L, 3.0)
        assertEquals(listOf(2.0, 3.0), history.snapshot())
    }

    @Test
    fun record_throttlesWithinMinInterval() {
        val history = LivePriceTickHistory(minIntervalMillis = 1_000L)
        history.record(0L, 10.0)
        history.record(500L, 11.0)
        assertEquals(listOf(10.0), history.snapshot())
    }

    @Test
    fun record_ignoresNonFinitePrices() {
        val history = LivePriceTickHistory(minIntervalMillis = 0L)
        history.record(0L, Double.NaN)
        history.record(1L, 100.0)
        history.record(2L, Double.POSITIVE_INFINITY)
        assertEquals(listOf(100.0), history.snapshot())
    }
}
