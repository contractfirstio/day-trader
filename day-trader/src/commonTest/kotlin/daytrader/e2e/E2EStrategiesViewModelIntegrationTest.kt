package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerId
import daytrader.presentation.strategies.StrategyDetailTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: [daytrader.presentation.strategies.StrategiesViewModel] wired through
 * [daytrader.ui.rememberAppDependencies]-style engine + broker integration.
 */
@E2EIbTest
class E2EStrategiesViewModelIntegrationTest {
    @Test
    fun viewModel_manualStart_navigatesToLiveTabAndShowsRunningStatus() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.stoppedDeployment())
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID)

            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.RUNNING)
            harness.awaitDetailTab(StrategyDetailTab.LIVE)
            assertEquals(
                DeploymentStatus.RUNNING,
                repository.deployments.value.single().status
            )
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_engineAutoStop_navigatesToSessionHistoryAndUpdatesStatusChip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.nonLiquidityOpeningBar()
            val gateway = FakeBrokerGateway(
                brokerId = BrokerId.INTERACTIVE_BROKERS,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar))
            )
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(
                E2ETestFixtures.stoppedDeployment().copy(
                    touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                        enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
                    )
                )
            )
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID)
            harness.engine.dispatch(
                TouchTurnCommand.LoadFirstCandle(
                    E2ETestFixtures.DEPLOYMENT_ID,
                    E2ETestFixtures.SESSION_DATE
                )
            )
            E2EEngineLiquidityHelper.bootstrapAndAwaitLiquidity(harness.engine, repository)

            harness.awaitDetailTab(StrategyDetailTab.SESSION_HISTORY)
            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.STOPPED)

            val row = harness.viewModel.listState.value.filteredRows.single()
            assertEquals("Neutral", row.statusChipLabel)

            val deployment = repository.deployments.value.single()
            E2EEngineLiquidityHelper.assertNoTradeOutcome(
                deployment,
                TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
            )
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_startBlockedByOpenPosition_surfacesAlertAndDoesNotStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            gateway.setPositions(
                listOf(
                    AccountPosition(
                        account = "DU123",
                        symbol = E2ETestFixtures.SYMBOL,
                        companyName = "Apple Inc.",
                        quantity = 50,
                        avgPrice = 100.0,
                        marketPrice = 101.0,
                        priorClose = 99.0,
                        totalUnrealizedPnL = 50.0,
                        currency = "USD"
                    )
                )
            )
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.stoppedDeployment())
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID)

            harness.awaitStartBlockedAlert()
            val alert = harness.viewModel.chromeState.value.startBlockedAlert
            assertNotNull(alert)
            assertEquals(E2ETestFixtures.SYMBOL, alert.instanceSymbol)
            assertTrue(alert.summary.contains(E2ETestFixtures.SYMBOL))

            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.STOPPED)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_shutdownRunningSessions_stopsActiveDeployment() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.runningDeployment())
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()

            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.RUNNING)

            harness.viewModel.shutdownRunningSessions()

            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.STOPPED)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
            assertEquals(false, harness.viewModel.hasRunningSessions())
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_brokerPositionUpdate_refreshesListRowUnrealizedPnL() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.runningDeployment())
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()

            gateway.setPositions(
                listOf(
                    AccountPosition(
                        account = "DU123",
                        symbol = E2ETestFixtures.SYMBOL,
                        companyName = "Apple Inc.",
                        quantity = 10,
                        avgPrice = 100.0,
                        marketPrice = 105.0,
                        priorClose = 99.0,
                        totalUnrealizedPnL = 50.0,
                        currency = "USD"
                    )
                )
            )
            harness.awaitListRowPositionPnL(E2ETestFixtures.DEPLOYMENT_ID, 50.0)

            gateway.setPositions(
                listOf(
                    AccountPosition(
                        account = "DU123",
                        symbol = E2ETestFixtures.SYMBOL,
                        companyName = "Apple Inc.",
                        quantity = 10,
                        avgPrice = 100.0,
                        marketPrice = 108.0,
                        priorClose = 99.0,
                        totalUnrealizedPnL = 80.0,
                        currency = "USD"
                    )
                )
            )
            harness.awaitListRowPositionPnL(E2ETestFixtures.DEPLOYMENT_ID, 80.0)

            val row = harness.viewModel.listState.value.filteredRows.single()
            assertEquals(true, row.hasOpenPosition)
            assertNull(harness.viewModel.chromeState.value.startBlockedAlert)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
