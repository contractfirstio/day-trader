package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.presentation.strategies.DeploymentFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: deployment status filter + search through [StrategiesViewModel] list state.
 */
class E2EMultiDeploymentFilterTest {
    @Test
    fun viewModel_deploymentFilter_running_showsOnlyActiveDeployments() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = E2EStrategiesViewModelHarness.create(
                scope,
                repository,
                FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            )
            repository.add(E2ETestFixtures.runningDeployment(symbol = "AAPL"))
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "MSFT",
                    maxDollars = 500,
                    status = DeploymentStatus.STOPPED
                ).copy(id = E2ETestFixtures.DEPLOYMENT_ID_2)
            )
            harness.start()
            harness.marketFilter.clear()
            harness.awaitListFilter("market filter cleared with both deployments visible") { state ->
                state.selectedMarketZoneId == null && state.filteredRows.size == 2
            }

            harness.viewModel.onDeploymentFilterChange(DeploymentFilter.RUNNING)
            val listState = harness.awaitListFilter("RUNNING deployment filter") { state ->
                state.deploymentFilter == DeploymentFilter.RUNNING &&
                    state.filteredRows.size == 1 &&
                    state.filteredRows.single().id == E2ETestFixtures.DEPLOYMENT_ID &&
                    state.filteredRows.single().status == DeploymentStatus.RUNNING &&
                    state.filteredCount == 1 &&
                    state.totalCount == 2
            }
            assertEquals(E2ETestFixtures.DEPLOYMENT_ID, listState.filteredRows.single().id)
            assertEquals(DeploymentStatus.RUNNING, listState.filteredRows.single().status)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_deploymentFilter_stopped_showsOnlyStoppedDeployments() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = E2EStrategiesViewModelHarness.create(
                scope,
                repository,
                FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            )
            repository.add(E2ETestFixtures.runningDeployment(symbol = "AAPL"))
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "MSFT",
                    maxDollars = 500,
                    status = DeploymentStatus.STOPPED
                ).copy(id = E2ETestFixtures.DEPLOYMENT_ID_2)
            )
            harness.start()
            harness.marketFilter.clear()
            harness.awaitListFilter("market filter cleared with both deployments visible") { state ->
                state.selectedMarketZoneId == null && state.filteredRows.size == 2
            }

            harness.viewModel.onDeploymentFilterChange(DeploymentFilter.STOPPED)
            val listState = harness.awaitListFilter("STOPPED deployment filter") { state ->
                state.deploymentFilter == DeploymentFilter.STOPPED &&
                    state.filteredRows.size == 1 &&
                    state.filteredRows.single().id == E2ETestFixtures.DEPLOYMENT_ID_2 &&
                    state.filteredRows.single().status == DeploymentStatus.STOPPED
            }
            assertEquals(E2ETestFixtures.DEPLOYMENT_ID_2, listState.filteredRows.single().id)
            assertEquals(DeploymentStatus.STOPPED, listState.filteredRows.single().status)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_searchQuery_filtersBySymbolSubstring() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = E2EStrategiesViewModelHarness.create(
                scope,
                repository,
                FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            )
            repository.add(E2ETestFixtures.stoppedDeployment(symbol = "AAPL"))
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "MSFT",
                    maxDollars = 500,
                    status = DeploymentStatus.STOPPED
                ).copy(id = E2ETestFixtures.DEPLOYMENT_ID_2)
            )
            harness.start()
            harness.marketFilter.clear()
            harness.awaitListFilter("market filter cleared with both deployments visible") { state ->
                state.selectedMarketZoneId == null && state.filteredRows.size == 2
            }

            harness.viewModel.onSearchChange("msf")
            val listState = harness.awaitListFilter("search query \"msf\"") { state ->
                state.searchQuery == "msf" &&
                    state.filteredRows.size == 1 &&
                    state.filteredRows.single().name.contains("MSFT", ignoreCase = true) &&
                    state.hasActiveFilters
            }
            assertTrue(listState.filteredRows.single().name.contains("MSFT", ignoreCase = true))
            assertTrue(listState.hasActiveFilters)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
