package daytrader.presentation.watchlist

import daytrader.domain.WatchlistPlanDiaryEntry
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.DEFAULT_WATCHLIST_ID
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class WatchlistPlanDiaryDeleteTest {
    @Test
    fun repositoryUpdate_canRemoveDiaryEntry() {
        val diaryId = "d1"
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
                    diaryEntries = listOf(
                        WatchlistPlanDiaryEntry(
                            id = diaryId,
                            body = "Remove me",
                            createdAtEpochMs = 1L
                        )
                    )
                )
            )
        )
        val repository = InMemoryWatchlistRepository(
            initial = listOf(defaultWatchlist().copy(entries = listOf(entry)))
        )

        repository.updateWatchlist(DEFAULT_WATCHLIST_ID) { watchlist ->
            watchlist.copy(
                entries = watchlist.entries.map { watchlistEntry ->
                    if (watchlistEntry.id != entry.id) watchlistEntry
                    else watchlistEntry.copy(
                        tradePlans = watchlistEntry.tradePlans.map { plan ->
                            if (plan.id != "plan-a") plan
                            else plan.copy(diaryEntries = plan.diaryEntries.filterNot { it.id == diaryId })
                        }
                    )
                }
            )
        }

        assertEquals(
            0,
            repository.watchlists.value.first().entries.single().tradePlans.single().diaryEntries.size
        )
    }

    @Test
    fun deleteDiaryEntry_removesFromPersistenceAndClosesEditorDraft() = runBlocking {
        val planId = "plan-a"
        val diaryId = "d1"
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
                    diaryEntries = listOf(
                        WatchlistPlanDiaryEntry(
                            id = diaryId,
                            body = "Remove me",
                            createdAtEpochMs = 1L
                        )
                    )
                )
            )
        )
        val repository = InMemoryWatchlistRepository(
            initial = listOf(defaultWatchlist().copy(entries = listOf(entry)))
        )
        val viewModel = WatchlistViewModel(
            repository = repository,
            brokerGateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR),
            brokerKind = BrokerKind.EMULATOR
        )

        withTimeout(5_000) {
            while (viewModel.uiState.value.totalEntryCount == 0) delay(25)
        }

        viewModel.onOpenTradePlans(entry.id)
        withTimeout(5_000) {
            while (viewModel.uiState.value.tradePlansEditor == null) delay(25)
        }
        val loadedPlanId = repository.watchlists.value.first().entries.single().tradePlans.single().id
        viewModel.onOpenPlanDiary(loadedPlanId)
        withTimeout(5_000) {
            while (viewModel.uiState.value.planDiaryEditor == null) delay(25)
        }
        viewModel.onDeleteDiaryEntry(diaryId)

        assertEquals(0, repository.watchlists.value.first().entries.single().tradePlans.single().diaryEntries.size)
        assertEquals(0, viewModel.uiState.value.planDiaryEditor?.entries?.size)
        assertNull(viewModel.uiState.value.planDiaryEditor?.editingEntryId)
    }
}
