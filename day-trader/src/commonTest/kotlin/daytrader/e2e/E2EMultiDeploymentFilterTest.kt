package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.E2EStrategiesViewModelHarness
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: deployment status filter + search through [StrategiesViewModel] list state.
 */
class E2EMultiDeploymentFilterTest {
    @Test
    fun viewModel_deploymentFilter_running_showsOnlyActiveDeployments() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = E2EStrategiesViewModelHarness.create(
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

            harness.viewModel.onDeploymentFilterChange(DeploymentFilter.RUNNING)
            delay(50)

            val rows = harness.viewModel.listState.value.filteredRows
            assertEquals(1, rows.size)
            assertEquals(E2ETestFixtures.DEPLOYMENT_ID, rows.single().id)
            assertEquals(DeploymentStatus.RUNNING, rows.single().status)
            assertEquals(1, harness.viewModel.listState.value.filteredCount)
            assertEquals(2, harness.viewModel.listState.value.totalCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewModel_deploymentFilter_stopped_showsOnlyStoppedDeployments() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = E2EStrategiesViewModelHarness.create(
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

            harness.viewModel.onDeploymentFilterChange(DeploymentFilter.STOPPED)
            delay(50)

            val rows = harness.viewModel.listState.value.filteredRows
            assertEquals(1, rows.size)
            assertEquals(E2ETestFixtures.DEPLOYMENT_ID_2, rows.single().id)
            assertEquals(DeploymentStatus.STOPPED, rows.single().status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewModel_searchQuery_filtersBySymbolSubstring() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = E2EStrategiesViewModelHarness.create(
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

            harness.viewModel.onSearchChange("msf")
            delay(300)

            val rows = harness.viewModel.listState.value.filteredRows
            assertEquals(1, rows.size)
            assertTrue(rows.single().name.contains("MSFT", ignoreCase = true))
            assertTrue(harness.viewModel.listState.value.hasActiveFilters)
        } finally {
            scope.cancel()
        }
    }
}
