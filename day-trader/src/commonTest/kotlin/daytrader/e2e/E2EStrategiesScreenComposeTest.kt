package daytrader.e2e

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.closeE2EHarness
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.ui.StrategiesScreen
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Compose UI smoke: Strategies screen renders deployment list from wired ViewModel.
 */
@OptIn(ExperimentalTestApi::class)
class E2EStrategiesScreenComposeTest {
    @Test
    fun strategiesScreen_rendersDeploymentListAndDetail() = runComposeUiTest {
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
            repository.add(E2ETestFixtures.stoppedDeployment())
            harness!!.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()
            harness.marketFilter.clear()

            setContent {
                MaterialTheme {
                    StrategiesScreen(viewModel = harness!!.viewModel)
                }
            }

            onNodeWithTag("StrategiesScreen").assertIsDisplayed()
            onNodeWithTag("StrategyDeploymentList").assertIsDisplayed()
            onNodeWithTag("StrategyDeploymentDetail").assertIsDisplayed()
            onNodeWithTag("StrategiesFilterPanel").assertIsDisplayed()
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
