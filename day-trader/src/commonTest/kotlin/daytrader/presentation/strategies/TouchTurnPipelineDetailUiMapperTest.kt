package daytrader.presentation.strategies

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TouchTurnPipelineDetailUiMapperTest {
    @Test
    fun sessionDataCapture_includesAdrAndOpeningBar() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = "20260522  09:30:00"),
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            rangeThreshold = 2.5,
            adr14 = 10.0,
            milestones = daytrader.domain.TouchTurnMilestoneTimestamps(dataReadyAt = "2026-05-22T09:30:12")
        )
        val capture = TouchTurnPipelineDetailUiMapper.sessionDataCapture(session)
        assertTrue(capture.isReady)
        assertEquals(10.0, capture.adr14)
        assertEquals(2.5, capture.rangeThreshold)
        assertEquals("20260522  09:30:00", capture.candle?.time)
        assertEquals("2026-05-22T09:30:12", capture.dataReadyAt)
        assertEquals(25, capture.adrRatioPercent)
    }

    @Test
    fun openingBarDetail_includesAllPrices() {
        val barTime = "20260522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!!
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = barTime),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            adr14 = 10.0
        )
        val detail = TouchTurnPipelineDetailUiMapper.openingBarDetail(session, barEnd - 1)
        assertNotNull(detail)
        assertEquals(FirstCandleCloseStatus.FORMING, detail.closeStatus)
        assertNotNull(detail.timeUntilCloseLabel)
        assertEquals(6.0, detail.range)
    }

    @Test
    fun liquidityCalculation_showsPassWhenRangeExceedsThreshold() {
        val barTime = "20260522  09:30:00"
        val now = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!! + 1
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = barTime),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            adr14 = 10.0
        )
        val calc = TouchTurnPipelineDetailUiMapper.liquidityCalculation(session, now)
        assertNotNull(calc)
        assertEquals(LiquidityCandleEvaluation.LIQUIDITY, calc.evaluation)
        assertEquals(true, calc.passes)
        assertTrue(calc.canCompare)
        assertEquals(6.0, calc.barRange)
    }
}
