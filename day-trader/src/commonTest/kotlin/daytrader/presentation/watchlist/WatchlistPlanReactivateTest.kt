package daytrader.presentation.watchlist

import daytrader.domain.Watchlist
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class WatchlistPlanReactivateTest {
    @Test
    fun reactivatePlan_clearsOrderPlacementAndRestoresEditorState() = runBlocking {
        val planId = "plan-a"
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = planId,
                    label = "Plan A",
                    entryPrice = 100.0,
                    stopPrice = 95.0,
                    targetPrice = 110.0,
                    investmentAmount = 1_000.0,
                    orderPlacedAtEpochMs = 1_700_000_000_000L,
                    placedOrderIds = listOf(100, 101, 102)
                )
            )
        )
        val watchlist: Watchlist = defaultWatchlist().copy(entries = listOf(entry))
        val repository = InMemoryWatchlistRepository(initial = listOf(watchlist))
        val viewModel = WatchlistViewModel(
            repository = repository,
            brokerGateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR),
            brokerKind = BrokerKind.EMULATOR
        )

        withTimeout(5_000) {
            while (viewModel.uiState.value.totalEntryCount == 0) {
                delay(25)
            }
        }

        viewModel.onOpenTradePlans(entry.id)
        val editorBefore = viewModel.uiState.value.tradePlansEditor?.plans?.single()
        assertTrue(editorBefore?.orderPlacedLabel != null)

        viewModel.onReactivatePlan(planId)

        val storedPlan = repository.watchlists.value.first().entries.single().tradePlans.single()
        assertFalse(storedPlan.hasPlacedOrder)
        assertTrue(storedPlan.placedOrderIds.isEmpty())

        val editorAfter = viewModel.uiState.value.tradePlansEditor?.plans?.single()
        assertNull(editorAfter?.orderPlacedLabel)
    }
}