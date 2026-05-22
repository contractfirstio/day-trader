package daytrader.presentation.markets

import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarketFilterStateTest {
    private fun saturdayMiddayMillis(): Long =
        LocalDate.of(2026, 5, 23)
            .atTime(12, 0)
            .atZone(ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

    @Test
    fun startupDefault_selectsUsWhenUsRthIsOpen() {
        val filter = MarketFilterState()
        val midday = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!! +
            2 * 60 * 60 * 1000

        filter.applyStartupDefaultIfNeeded(midday)

        assertEquals(RthMarketSessions.US.zoneId, filter.selectedZoneId.value)
    }

    @Test
    fun startupDefault_runsOnlyOnce() {
        val filter = MarketFilterState()
        val midday = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!! +
            2 * 60 * 60 * 1000
        filter.applyStartupDefaultIfNeeded(midday)
        filter.clear()
        filter.applyStartupDefaultIfNeeded(midday)

        assertNull(filter.selectedZoneId.value)
    }

    @Test
    fun startupDefault_notReappliedAfterUserClearsFilters() {
        val filter = MarketFilterState()
        val midday = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!! +
            2 * 60 * 60 * 1000
        filter.applyStartupDefaultIfNeeded(midday)
        filter.clear()

        assertNull(filter.selectedZoneId.value)
    }

    @Test
    fun startupDefault_notReappliedAfterUserDeselectsMarketCard() {
        val filter = MarketFilterState()
        val midday = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "America/New_York", null)!! +
            2 * 60 * 60 * 1000
        filter.applyStartupDefaultIfNeeded(midday)
        filter.toggle(RthMarketSessions.US.zoneId)

        assertNull(filter.selectedZoneId.value)
    }

    @Test
    fun startupDefault_leavesNullWhenNoMarketIsOpen() {
        val filter = MarketFilterState()

        filter.applyStartupDefaultIfNeeded(saturdayMiddayMillis())

        assertNull(filter.selectedZoneId.value)
    }
}
