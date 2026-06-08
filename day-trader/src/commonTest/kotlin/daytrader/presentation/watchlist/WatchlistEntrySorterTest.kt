package daytrader.presentation.watchlist

import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistEntrySorterTest {
    @Test
    fun sortedEntries_groupsByLabelName() {
        val earnings = WatchlistLabel(id = "lbl-earnings", name = "Earnings", createdAtEpochMs = 1L)
        val tech = WatchlistLabel(id = "lbl-tech", name = "Tech", createdAtEpochMs = 1L)
        val watchlist = defaultWatchlist().copy(
            labels = listOf(earnings, tech),
            entries = listOf(
                newWatchlistEntry("MSFT", "America/New_York", "USD", "Microsoft", null)
                    .copy(labelIds = listOf(tech.id)),
                newWatchlistEntry("AAPL", "America/New_York", "USD", "Apple", null)
                    .copy(labelIds = listOf(earnings.id)),
                newWatchlistEntry("ZZZZ", "America/New_York", "USD", "Zeta Corp", null)
            )
        )

        val sorted = WatchlistEntrySorter.sortedEntries(
            entries = watchlist.entries,
            column = WatchlistSortColumn.GROUPS,
            direction = WatchlistSortDirection.ASCENDING,
            watchlist = watchlist,
            deployments = emptyList()
        )

        assertEquals(listOf("AAPL", "MSFT", "ZZZZ"), sorted.map { it.symbol })
    }

    @Test
    fun sortedEntries_statusPutsNearEntriesFirstWhenAscending() {
        val entries = listOf(
            newWatchlistEntry("CLEAR", "America/New_York", "USD", "Clear Co", null)
                .copy(lastScannedPrice = 100.0, lastScannedAtEpochMs = 1L),
            newWatchlistEntry("NEAR", "America/New_York", "USD", "Near Co", null)
                .copy(
                    lastScannedPrice = 101.0,
                    lastScannedAtEpochMs = 1L,
                    tradePlans = listOf(
                        WatchlistTradePlan(
                            id = "plan-a",
                            label = "Plan A",
                            entryPrice = 100.0,
                            proximityAlertEnabled = true,
                            proximityThresholdValue = 5.0
                        )
                    )
                ),
            newWatchlistEntry("NONE", "America/New_York", "USD", "None Co", null)
        )

        val sorted = WatchlistEntrySorter.sortedEntries(
            entries = entries,
            column = WatchlistSortColumn.STATUS,
            direction = WatchlistSortDirection.ASCENDING,
            watchlist = defaultWatchlist(),
            deployments = emptyList()
        )

        assertEquals(listOf("NEAR", "CLEAR", "NONE"), sorted.map { it.symbol })
    }

    @Test
    fun sortedEntries_strategiesByLinkedDeploymentName() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 10_000
        )
        val entries = listOf(
            newWatchlistEntry("AAPL", "America/New_York", "USD", "Apple", null)
                .copy(strategyDeploymentIds = listOf(deployment.id)),
            newWatchlistEntry("MSFT", "America/New_York", "USD", "Microsoft", null)
        )

        val sorted = WatchlistEntrySorter.sortedEntries(
            entries = entries,
            column = WatchlistSortColumn.STRATEGIES,
            direction = WatchlistSortDirection.ASCENDING,
            watchlist = defaultWatchlist(),
            deployments = listOf(deployment)
        )

        assertEquals(listOf("AAPL", "MSFT"), sorted.map { it.symbol })
    }
}
