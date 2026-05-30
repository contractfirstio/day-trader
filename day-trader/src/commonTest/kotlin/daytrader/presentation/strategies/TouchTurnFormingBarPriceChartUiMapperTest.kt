package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnFormingBarPriceChartUiMapperTest {
    private fun deployment() = StrategyDeployment(
        id = "d1",
        symbol = "META",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = DeploymentStatus.RUNNING,
        maxDollars = 500
    )

    private fun formingSession() = TouchTurnSessionContext(
        sessionDate = "2030-12-01",
        status = TouchTurnCandleStatus.READY,
        candle = OhlcBar(
            open = 100.0,
            high = 101.0,
            low = 99.0,
            close = 100.5,
            // Far-future bar open so wall-clock tests stay in FORMING.
            time = "20301201  09:30:00"
        ),
        marketZoneId = "America/New_York"
    )

    @Test
    fun shouldRecordPrices_onlyWhileBarIsForming() {
        val session = formingSession()
        assertTrue(TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(session))
        assertFalse(
            TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(
                session.copy(status = TouchTurnCandleStatus.LOADING)
            )
        )
    }

    @Test
    fun build_returnsChartDuringForming_evenBeforeFirstTick() {
        val chart = TouchTurnFormingBarPriceChartUiMapper.build(
            deployment = deployment(),
            session = formingSession(),
            priceHistory = emptyList(),
            currentPrice = null
        )
        assertNotNull(chart)
        assertEquals(TouchTurnPriceChartContext.OPENING_BAR_FORMING, chart.context)
        assertTrue(chart.levels.isEmpty())
    }

    @Test
    fun build_nullWhenCandleUnavailable() {
        assertNull(
            TouchTurnFormingBarPriceChartUiMapper.build(
                deployment = deployment(),
                session = formingSession().copy(candle = null),
                priceHistory = listOf(100.0),
                currentPrice = 100.0
            )
        )
    }

    @Test
    fun build_carriesStreamingHistory() {
        val chart = TouchTurnFormingBarPriceChartUiMapper.build(
            deployment = deployment(),
            session = formingSession(),
            priceHistory = listOf(100.0, 100.5),
            currentPrice = 100.5
        )
        assertNotNull(chart)
        assertEquals(listOf(100.0, 100.5), chart.priceHistory)
        assertEquals(100.5, chart.currentPrice)
    }
}
