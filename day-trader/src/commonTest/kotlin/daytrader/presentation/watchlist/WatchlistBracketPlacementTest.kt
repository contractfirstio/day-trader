package daytrader.presentation.watchlist

import daytrader.domain.PlanSizingMode
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.domain.newWatchlistTradePlanId
import daytrader.e2e.support.HybridModeTestHarness
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class WatchlistBracketPlacementTest {

    @Test
    fun submitBracket_ibMode_routesToExecutionGateway() = runBlocking {
        val execution = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        val repository = repositoryWithCompletePlan()
        val viewModel = WatchlistViewModel(
            repository = repository,
            brokerGateway = execution,
            brokerKind = BrokerKind.INTERACTIVE_BROKERS
        )
        openAndSubmitBracket(viewModel, repository)

        assertEquals(1, execution.placedBrackets.size)
        assertEquals("AAPL", execution.placedBrackets.single().symbol)
        awaitBracketPlaced(viewModel, repository)
    }

    @Test
    fun submitBracket_hybridMode_usesExecutionGatewayNotMarketData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val harness = HybridModeTestHarness(scope)
        harness.start()
        try {
            val repository = repositoryWithCompletePlan()
            val viewModel = WatchlistViewModel(
                repository = repository,
                brokerGateway = harness.executionGateway,
                touchTurnSessionGateway = harness.ibGateway,
                brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
                ensureLiveMarketData = { symbol, _ -> harness.ibGateway.ensureStreaming(symbol) }
            )
            awaitExecutionConnected(viewModel)
            openAndSubmitBracket(viewModel, repository)

            awaitBracketPlaced(viewModel, repository)
            assertTrue(harness.ibGateway.ensureLiveMarketDataCalls.contains("AAPL"))
        } finally {
            harness.shutdown()
        }
    }

    private suspend fun awaitExecutionConnected(viewModel: WatchlistViewModel) {
        withTimeout(5_000) {
            while (viewModel.uiState.value.connectionLabel.contains("Paper execution connected").not()) {
                delay(25)
            }
        }
    }

    private suspend fun awaitBracketPlaced(
        viewModel: WatchlistViewModel,
        repository: InMemoryWatchlistRepository
    ) {
        withTimeout(5_000) {
            while (true) {
                val plan = repository.watchlists.value.first().entries.single().tradePlans.single()
                if (plan.hasPlacedOrder && viewModel.uiState.value.bracketOrderEditor == null) {
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    private fun openAndSubmitBracket(
        viewModel: WatchlistViewModel,
        repository: InMemoryWatchlistRepository
    ) {
        val entryId = repository.watchlists.value.first().entries.single().id
        val planId = repository.watchlists.value.first().entries.single().tradePlans.single().id
        viewModel.onOpenTradePlans(entryId)
        viewModel.onOpenBracketOrder(planId)
        viewModel.onSubmitBracketOrder()
    }

    private fun repositoryWithCompletePlan(): InMemoryWatchlistRepository {
        val planId = newWatchlistTradePlanId()
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null,
            notes = null
        ).copy(
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = planId,
                    label = "Plan A",
                    side = TradeSide.LONG,
                    entryPrice = 100.0,
                    stopPrice = 95.0,
                    targetPrice = 110.0,
                    investmentAmount = 1_000.0,
                    sizingMode = PlanSizingMode.NOTIONAL
                )
            )
        )
        val watchlist: Watchlist = defaultWatchlist().copy(entries = listOf(entry))
        return InMemoryWatchlistRepository(initial = listOf(watchlist))
    }
}
