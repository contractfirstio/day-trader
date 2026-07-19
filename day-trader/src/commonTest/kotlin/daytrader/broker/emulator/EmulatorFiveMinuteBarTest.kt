package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnTradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmulatorFiveMinuteBarTest {
    @Test
    fun fiveMinuteBarsSince_emitsHammerOnConfiguredIndex() {
        val opening = OhlcBar(
            open = 110.0,
            high = 110.0,
            low = 100.0,
            close = 100.2,
            time = "20260522  09:30:00"
        )
        val config = BrokerEmulatorConfig(
            fiveMinuteBarSecondsUntilClose = 1L,
            fiveMinuteHammerBarIndex = 1
        )
        val sweepStart = System.currentTimeMillis() - 2_500L
        val bars = EmulatorHistoricalData.fiveMinuteBarsSince(
            openingFifteenMinuteBar = opening,
            side = TouchTurnTradeSide.LONG,
            config = config,
            afterBarOpenEpochMs = sweepStart,
            marketZoneId = "America/New_York",
            nowEpochMillis = System.currentTimeMillis()
        )
        assertTrue(bars.size >= 2, "expected at least two closed 5m bars, got ${bars.size}")
        assertEquals(opening.close, bars[0].open, "first 5m open should match 15m close")
        assertEquals(bars[0].close, bars[1].open, "second 5m open should match prior 5m close")
        val hammer = bars[1]
        assertTrue(
            FiveMinuteConfirmationLogic.isHammerPattern(hammer, TouchTurnTradeSide.LONG)
        )
        assertTrue(hammer.close in opening.low..opening.high)
    }

    @Test
    fun fiveMinuteBarsSince_emitsEngulfingOnFirstWindowBarWithPreWindowPrior() {
        val opening = OhlcBar(
            open = 110.0,
            high = 110.0,
            low = 100.0,
            close = 100.2,
            time = "20260522  09:30:00"
        )
        val config = BrokerEmulatorConfig(
            fiveMinuteBarSecondsUntilClose = 1L,
            fiveMinuteHammerBarIndex = -1,
            fiveMinuteEngulfingBarIndex = 0
        )
        val windowStart = System.currentTimeMillis() - 2_500L
        val bars = EmulatorHistoricalData.fiveMinuteBarsSince(
            openingFifteenMinuteBar = opening,
            side = TouchTurnTradeSide.LONG,
            config = config,
            afterBarOpenEpochMs = windowStart - 1_000L,
            marketZoneId = "America/New_York",
            nowEpochMillis = System.currentTimeMillis(),
            windowStartEpochMs = windowStart
        )
        assertTrue(bars.size >= 2, "expected prior + engulfing bar, got ${bars.size}")
        val prior = bars[0]
        val engulfing = bars[1]
        assertTrue(
            FiveMinuteConfirmationLogic.isEngulfingPattern(prior, engulfing, TouchTurnTradeSide.LONG)
        )
        assertTrue(
            !FiveMinuteConfirmationLogic.isHammerPattern(engulfing, TouchTurnTradeSide.LONG)
        )
    }
}
