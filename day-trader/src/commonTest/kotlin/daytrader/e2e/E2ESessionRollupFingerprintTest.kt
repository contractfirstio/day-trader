package daytrader.e2e

import daytrader.e2e.support.E2ESessionRollupHelper
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.presentation.strategies.StrategyDetailTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: replacing a closed session row invalidates cached rollups while preserving totals.
 */
@E2EEmulatorTest
class E2ESessionRollupFingerprintTest {
    @Test
    fun viewModel_sessionHistoryReplacement_keepsRollupTotalsAndUpdatesRowId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            harness = E2EStrategiesViewModelHarness.createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = BrokerKind.EMULATOR,
            )
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            repository.add(
                E2ETestFixtures.stoppedDeployment().copy(
                    sessionHistory = listOf(
                        E2ESessionRollupHelper.closedTradedSession(id = "run-a", pnl = 25.0)
                    )
                )
            )
            harness.selectDeployment(deploymentId)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.SESSION_HISTORY)
            delay(50)

            val expectedBefore = E2ESessionRollupHelper.expectedRollupUi(
                repository.deployments.value.single { it.id == deploymentId }
            )
            harness.awaitListRowTotalPnL(deploymentId, expectedBefore.formattedTotalPnL)

            repository.update(deploymentId) { current ->
                current.copy(
                    sessionHistory = listOf(
                        E2ESessionRollupHelper.closedTradedSession(
                            id = "run-b",
                            pnl = 25.0,
                            stoppedAt = "${E2ETestFixtures.SESSION_DATE}T11:15:00"
                        )
                    )
                )
            }
            delay(50)

            val expectedAfter = E2ESessionRollupHelper.expectedRollupUi(
                repository.deployments.value.single { it.id == deploymentId }
            )
            assertEquals(expectedBefore.formattedTotalPnL, expectedAfter.formattedTotalPnL)
            harness.awaitListRowTotalPnL(deploymentId, expectedAfter.formattedTotalPnL)

            val history = harness.viewModel.detailState.value.sessionHistory
            assertNotNull(history)
            assertEquals("run-b", history.rows.single().id)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
