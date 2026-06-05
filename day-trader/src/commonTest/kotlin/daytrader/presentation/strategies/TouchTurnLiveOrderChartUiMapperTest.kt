package daytrader.presentation.strategies

import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnLiveOrderChartUiMapperTest {
    private fun session() = TouchTurnSessionContext(
        sessionDate = "2030-12-01",
        status = TouchTurnCandleStatus.READY,
        marketZoneId = "America/New_York"
    )

    @Test
    fun shouldRecordPrices_whenOrdersPlacedOrEntryPermitted() {
        assertFalse(TouchTurnLiveOrderChartUiMapper.shouldRecordPrices(session()))
        assertTrue(
            TouchTurnLiveOrderChartUiMapper.shouldRecordPrices(
                session().copy(ordersPlacedForSession = true)
            )
        )
        assertTrue(
            TouchTurnLiveOrderChartUiMapper.shouldRecordPrices(
                session().copy(entryOrdersPermitted = true)
            )
        )
    }
}
