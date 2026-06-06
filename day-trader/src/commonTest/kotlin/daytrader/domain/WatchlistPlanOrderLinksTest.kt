package daytrader.domain

import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistPlanOrderLinksTest {
    @Test
    fun planLabelForOrder_matchesStoredOrderIds() {
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = "plan-a",
                    label = "Plan A",
                    orderPlacedAtEpochMs = 1L,
                    placedOrderIds = listOf(100, 101, 102)
                )
            )
        )
        val watchlists = listOf(defaultWatchlist().copy(entries = listOf(entry)))

        assertEquals("AAPL · Plan A", WatchlistPlanOrderLinks.planLabelForOrder(101, watchlists))
        assertEquals(null, WatchlistPlanOrderLinks.planLabelForOrder(999, watchlists))
    }
}
