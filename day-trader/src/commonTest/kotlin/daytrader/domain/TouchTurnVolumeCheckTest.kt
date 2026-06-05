package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnVolumeCheckTest {
    @Test
    fun buildVolumeCheck_computesThresholdAndRatio() {
        val check = TouchTurnVolumeCheck.build(
            phase = TouchTurnVolumeCheckPhase.LIQUIDITY_EVALUATED,
            candleVolume = 20_000.0,
            volumeSma20 = 10_000.0,
            barTime = "20260604  09:30:00"
        )!!

        assertEquals(TouchTurnVolumeCheckPhase.LIQUIDITY_EVALUATED, check.phase)
        assertEquals(20_000.0, check.openingBarVolume)
        assertEquals(10_000.0, check.volumeSma20)
        assertEquals(15_000.0, check.exhaustionThreshold, 0.01)
        assertEquals(2.0, check.volumeRatio!!, 0.01)
        assertTrue(check.volumeExhausted)
    }

    @Test
    fun buildVolumeCheck_notExhaustedBelowThreshold() {
        val check = TouchTurnVolumeCheck.build(
            phase = TouchTurnVolumeCheckPhase.SIGNAL_CONTEXT,
            candleVolume = 14_000.0,
            volumeSma20 = 10_000.0,
            barTime = null
        )!!

        assertFalse(check.volumeExhausted)
    }

    @Test
    fun traceDetailsFromSession_usesClosedBarVolume() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-04",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(
                open = 8.54,
                high = 8.58,
                low = 8.52,
                close = 8.56,
                time = "20260604  09:30:00",
                volume = 3_000_000.0
            ),
            volumeSma20 = 1_000_000.0,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION
        )
        val details = TouchTurnVolumeCheck.traceDetailsFromSession(session)
        assertEquals("3000000.0", details["openingBarVolume"])
        assertEquals("1000000.0", details["volumeSma20"])
        assertEquals("1500000.0", details["exhaustionThreshold"])
        assertEquals("true", details["volumeExhausted"])
    }
}
