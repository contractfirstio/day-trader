package daytrader.engine.touchturn

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import java.time.LocalDate
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
    fun deriveSignalContext_volumeSmaFromPriorSessionOpens_notIntradayTail() {
        val zone = "America/New_York"
        val session = "20260522"
        var day = LocalDate.of(2026, 5, 21)
        val priorOpenings = buildList {
            repeat(TouchTurnDefaults.VOLUME_SMA_PERIODS) {
                val ymd = "%04d%02d%02d".format(day.year, day.monthValue, day.dayOfMonth)
                add(
                    OhlcBar(
                        open = 100.0,
                        high = 101.0,
                        low = 99.0,
                        close = 100.5,
                        time = "$ymd  09:30:00",
                        volume = 10_000.0
                    )
                )
                day = TouchTurnLogic.previousRthTradingDay(day.minusDays(1))
            }
        }
        val intradayTail = (1..TouchTurnDefaults.ATR_LOOKBACK_PERIODS).map { slot ->
            val totalMinutes = 15 * 60 + slot * 15
            OhlcBar(
                open = 100.0,
                high = 100.2,
                low = 99.8,
                close = 100.1,
                time = "20260521  %02d:%02d:00".format(totalMinutes / 60, totalMinutes % 60),
                volume = 50.0
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
            bars = priorOpenings + intradayTail + opening,
            marketZoneId = zone,
            sessionDayYyyyMmdd = session
        )
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val ctx = result.getOrThrow()
        assertTrue(ctx.atr14 > 0.0)
        assertEquals(10_000.0, ctx.volumeSma20, 0.01)
        assertEquals(opening, ctx.firstCandle)
    }

    @Test
    fun bufferThreshold_matchesExhaustionRatio() {
        val threshold = VolumeExhaustionSignalEngine.bufferVolumeThreshold(10_000.0)
        assertEquals(10_000.0 * TouchTurnDefaults.VOLUME_EXHAUSTION_RATIO, threshold, 0.01)
    }
}
