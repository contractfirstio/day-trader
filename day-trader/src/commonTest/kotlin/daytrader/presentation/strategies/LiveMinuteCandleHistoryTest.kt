package daytrader.presentation.strategies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveMinuteCandleHistoryTest {
    @Test
    fun aggregatesTicksIntoOneMinuteCandle() {
        val history = LiveMinuteCandleHistory()
        history.record(0L, 100.0)
        history.record(15_000L, 105.0)
        history.record(30_000L, 98.0)

        val candle = history.snapshot().single()
        assertEquals(0L, candle.bucketStartMillis)
        assertEquals(100.0, candle.open)
        assertEquals(105.0, candle.high)
        assertEquals(98.0, candle.low)
        assertEquals(98.0, candle.close)
    }

    @Test
    fun rollsIntoNewCandleEachMinute() {
        val history = LiveMinuteCandleHistory()
        history.record(0L, 10.0)
        history.record(59_999L, 11.0)
        history.record(60_000L, 20.0)

        val candles = history.snapshot()
        assertEquals(2, candles.size)
        assertEquals(10.0, candles[0].open)
        assertEquals(11.0, candles[0].close)
        assertEquals(60_000L, candles[1].bucketStartMillis)
        assertEquals(20.0, candles[1].open)
        assertEquals(20.0, candles[1].close)
    }

    @Test
    fun trimsToMaxCandles() {
        val history = LiveMinuteCandleHistory(maxCandles = 2)
        history.record(0L, 1.0)
        history.record(60_000L, 2.0)
        history.record(120_000L, 3.0)
        history.record(180_000L, 4.0)

        assertEquals(3, history.snapshot().size)
        assertEquals(60_000L, history.snapshot().first().bucketStartMillis)
    }

    @Test
    fun clearRemovesAllCandles() {
        val history = LiveMinuteCandleHistory()
        history.record(1_000L, 10.0)
        history.clear()
        assertTrue(history.snapshot().isEmpty())
    }
}
