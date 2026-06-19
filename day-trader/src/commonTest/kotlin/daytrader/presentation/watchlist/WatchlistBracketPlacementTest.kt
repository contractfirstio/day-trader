package daytrader.presentation.watchlist

import daytrader.domain.PlanSizingMode
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.domain.newWatchlistTradePlanId
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.HybridModeTestHarness
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.gateway.BrokerGateway
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
        awaitExecutionConnected(viewModel, "Interactive Brokers connected")
        openAndSubmitBracket(viewModel, repository)

        awaitPlacedBracketCount(execution, 1)
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
            awaitExecutionConnected(viewModel, "Paper execution connected")
            seedHybridLiveQuote(harness)
            openAndSubmitBracket(viewModel, repository)

            awaitBracketPlaced(
                viewModel = viewModel,
                repository = repository,
                executionGateway = harness.executionGateway,
                symbol = "AAPL"
            )
            assertTrue(harness.ibGateway.ensureLiveMarketDataCalls.contains("AAPL"))
        } finally {
            harness.shutdown()
        }
    }

    private fun seedHybridLiveQuote(harness: HybridModeTestHarness) {
        val quote = E2ETestFixtures.liveQuote(
            symbol = "AAPL",
            bid = 99.9,
            ask = 100.1,
            last = 100.0
        )
        harness.publishIbQuote("AAPL", quote)
        harness.ingestLiveQuote("AAPL", quote)
    }

    private suspend fun awaitExecutionConnected(viewModel: WatchlistViewModel, connectedPhrase: String) {
        withTimeout(15_000) {
            while (viewModel.uiState.value.connectionLabel.contains(connectedPhrase).not()) {
                delay(25)
            }
        }
    }

    private suspend fun awaitPlacedBracketCount(gateway: FakeBrokerGateway, count: Int) {
        withTimeout(15_000) {
            while (gateway.placedBrackets.size < count) {
                delay(25)
            }
        }
    }

    private suspend fun awaitBracketPlaced(
        viewModel: WatchlistViewModel,
        repository: InMemoryWatchlistRepository,
        executionGateway: BrokerGateway? = null,
        symbol: String = "AAPL"
    ) {
        withTimeout(15_000) {
            while (true) {
                val plan = repository.watchlists.value.first().entries.single().tradePlans.single()
                val hasWorkingEntry = executionGateway?.openOrders?.value?.any {
                    it.symbol.equals(symbol, ignoreCase = true) && it.parentOrderId == 0 && it.remaining > 0
                } == true
                if (plan.hasPlacedOrder && viewModel.uiState.value.bracketOrderEditor == null) {
                    return@withTimeout
                }
                if (hasWorkingEntry && viewModel.uiState.value.bracketOrderEditor == null) {
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    private suspend fun openAndSubmitBracket(
        viewModel: WatchlistViewModel,
        repository: InMemoryWatchlistRepository
    ) {
        val entryId = repository.watchlists.value.first().entries.single().id
        val planId = repository.watchlists.value.first().entries.single().tradePlans.single().id
        viewModel.onOpenTradePlans(entryId)
        viewModel.onOpenBracketOrder(planId)
        withTimeout(5_000) {
            while (viewModel.uiState.value.bracketOrderEditor?.canSubmit != true) {
                delay(25)
            }
        }
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
