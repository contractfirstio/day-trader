package daytrader.engine.touchturn

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeExhaustionSignalEngineTest {
    @Test
    fun liquidityRangeThreshold_is25PercentOfAtr() {
        assertEquals(2.5, TouchTurnLogic.liquidityRangeThresholdFromAtr(10.0), 0.001)
    }

    @Test
    fun volumeExhaustion_whenCandleVolumeExceedsOnePointFiveTimesSma() {
        assertTrue(TouchTurnLogic.isVolumeExhaustion(candleVolume = 20_000.0, volumeSma20 = 10_000.0))
        assertFalse(TouchTurnLogic.isVolumeExhaustion(candleVolume = 14_000.0, volumeSma20 = 10_000.0))
    }

    @Test
    fun deriveSignalContext_fromFifteenMinuteHistory() {
        val prior = (0 until 20).map { i ->
            val totalMinutes = 4 * 60 + i * 15
            val hour = totalMinutes / 60
            val min = totalMinutes % 60
            OhlcBar(
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.5,
                time = "20260522  %02d:%02d:00".format(hour, min),
                volume = 10_000.0 + i * 100
            )
        }
        val opening = OhlcBar(
            open = 100.0,
            high = 105.0,
            low = 99.0,
            close = 104.0,
            time = "20260522  09:30:00",
            volume = 12_000.0
        )
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = prior + opening,
            marketZoneId = "America/New_York",
            sessionDayYyyyMmdd = "20260522"
        )
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val ctx = result.getOrThrow()
        assertTrue(ctx.atr14 > 0.0)
        assertTrue(ctx.volumeSma20 > 0.0)
        assertEquals(opening, ctx.firstCandle)
    }

    @Test
    fun bufferThreshold_matchesExhaustionRatio() {
        val threshold = VolumeExhaustionSignalEngine.bufferVolumeThreshold(10_000.0)
        assertEquals(10_000.0 * TouchTurnDefaults.VOLUME_EXHAUSTION_RATIO, threshold, 0.01)
    }
}
