package daytrader.data

import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.engine.support.InMemoryWatchlistRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchlistStrategyLinkSyncTest {
    @Test
    fun removeDeploymentFromAllWatchlists_clearsLinksAcrossEntries() {
        val deployment = defaultStrategyDeployment(StrategyType.TOUCH_AND_TURN_SCALPER, "AAPL", 1_000)
        val entryA = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(strategyDeploymentIds = listOf(deployment.id))
        val entryB = newWatchlistEntry(
            symbol = "MSFT",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Microsoft",
            instrument = null
        ).copy(strategyDeploymentIds = listOf(deployment.id))
        val watchlistRepo = InMemoryWatchlistRepository(
            listOf(defaultWatchlist().copy(entries = listOf(entryA, entryB)))
        )

        WatchlistStrategyLinkSync.removeDeploymentFromAllWatchlists(watchlistRepo, deployment.id)

        val restored = watchlistRepo.watchlists.value.single().entries
        assertTrue(restored.all { it.strategyDeploymentIds.isEmpty() })
    }

    @Test
    fun removeDeploymentFromAllWatchlists_leavesUnrelatedLinks() {
        val keep = defaultStrategyDeployment(StrategyType.TOUCH_AND_TURN_SCALPER, "AAPL", 1_000)
        val remove = defaultStrategyDeployment(StrategyType.QUICK_FLIP_SCALPER, "MSFT", 1_000)
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(strategyDeploymentIds = listOf(keep.id, remove.id))
        val watchlistRepo = InMemoryWatchlistRepository(
            listOf(defaultWatchlist().copy(entries = listOf(entry)))
        )

        WatchlistStrategyLinkSync.removeDeploymentFromAllWatchlists(watchlistRepo, remove.id)

        assertEquals(listOf(keep.id), watchlistRepo.watchlists.value.single().entries.single().strategyDeploymentIds)
    }
}
