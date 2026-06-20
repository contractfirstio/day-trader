package daytrader.e2e

import daytrader.domain.DeploymentMarket
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
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
 * End-to-end: HK vs US deployments respect market zone filter in Strategies list.
 */
class E2EHkMarketSessionTest {
    @Test
    fun viewModel_marketZoneFilter_hidesNonMatchingHongKongDeployment() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            val hkZone = "Asia/Hong_Kong"

            repository.add(E2ETestFixtures.stoppedDeployment(symbol = "AAPL"))
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "0700",
                    maxDollars = 500,
                    marketZoneId = hkZone,
                    currencyCode = "HKD",
                    status = DeploymentStatus.STOPPED,
                ).copy(id = E2ETestFixtures.DEPLOYMENT_ID_2)
            )
            harness.start()
            harness.marketFilter.select(hkZone)
            delay(50)

            val rows = harness.viewModel.listState.value.filteredRows
            assertEquals(1, rows.size)
            assertEquals(E2ETestFixtures.DEPLOYMENT_ID_2, rows.single().id)
            assertEquals(hkZone, DeploymentMarket.effectiveZoneId(
                repository.deployments.value.single { it.id == E2ETestFixtures.DEPLOYMENT_ID_2 }
            ))
            assertTrue(harness.viewModel.listState.value.hasActiveFilters)
        } finally {
            scope.cancel()
        }
    }
}
