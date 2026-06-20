package daytrader.e2e

import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: deleting a deployment through ViewModel clears watchlist strategy links.
 */
class E2EWatchlistStrategiesBridgeTest {
    @Test
    fun viewModel_deleteDeployment_clearsWatchlistStrategyLinks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val deployment = defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = E2ETestFixtures.SYMBOL,
                maxDollars = 500,
            ).copy(id = E2ETestFixtures.DEPLOYMENT_ID)
            val entry = newWatchlistEntry(
                symbol = E2ETestFixtures.SYMBOL,
                marketZoneId = "America/New_York",
                currencyCode = "USD",
                companyName = "Apple",
                instrument = null,
            ).copy(strategyDeploymentIds = listOf(deployment.id))
            val watchlistRepo = InMemoryWatchlistRepository(
                listOf(defaultWatchlist().copy(entries = listOf(entry)))
            )
            harness = E2EStrategiesViewModelHarness.createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = BrokerKind.INTERACTIVE_BROKERS,
                watchlistRepository = watchlistRepo,
            )
            repository.add(deployment)
            harness.selectDeployment(deployment.id)
            harness.start()

            harness.viewModel.onDeleteSelected()

            assertTrue(repository.deployments.value.isEmpty())
            assertTrue(
                watchlistRepo.watchlists.value.single().entries.single().strategyDeploymentIds.isEmpty()
            )
            assertEquals(0, harness.viewModel.listState.value.totalCount)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
