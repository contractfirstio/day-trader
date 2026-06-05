package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
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
        openingBarTime = "20301201  09:30:00",
        candle = null,
        marketZoneId = "America/New_York",
        milestones = TouchTurnMilestoneTimestamps(dataReadyAt = "2030-12-01T09:30:05")
    )

    @Test
    fun shouldRecordPrices_untilOrdersPlaced() {
        val session = formingSession()
        assertTrue(TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(session))
        assertFalse(
            TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(
                session.copy(ordersPlacedForSession = true)
            )
        )
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
    fun build_nullWhenOpeningBarTimeUnavailable() {
        assertNull(
            TouchTurnFormingBarPriceChartUiMapper.build(
                deployment = deployment(),
                session = formingSession().copy(openingBarTime = null),
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
