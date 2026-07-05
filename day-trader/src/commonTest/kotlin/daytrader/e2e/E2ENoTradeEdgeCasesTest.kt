package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketExitHelper
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.shutdownEmulatorHarness
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.presentation.strategies.StrategyDetailTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: additional no-trade engine paths through ViewModel wiring.
 */
class E2ENoTradeEdgeCasesTest {
    @E2EIbTest
    @Test
    fun viewModel_manualStopDuringRunning_clearsTouchTurnSession() = runBlocking {
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

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID)
            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.STOPPED)

            val deployment = repository.deployments.value.single()
            assertEquals(DeploymentStatus.STOPPED, deployment.status)
            assertNull(deployment.touchTurnSession)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @E2EEmulatorTest
    @Test
    fun viewModel_neverFillEntry_emulatorStaysRunningUntilManualStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        var emulatorHarness: EmulatorModeTestHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            emulatorHarness = daytrader.e2e.support.EmulatorModeTestHarness.neverFillEntry(scope)
            harness = E2EStrategiesViewModelHarness.createWithEmulator(scope, repository, emulatorHarness)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            E2EBracketExitHelper.seedLiquidityReadyDeployment(repository, deploymentId)
            harness.selectDeployment(deploymentId)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.LIVE)

            val plan = daytrader.e2e.support.E2EBracketHelper.liquidityPlan(symbol = symbol)
            emulatorHarness.gateway.placeTouchTurnBracket(plan)
            repository.update(deploymentId) { current ->
                current.withOrdersPlacedForSession(plan = plan)
            }

            harness.engine.dispatch(TouchTurnCommand.PollStopRules)
            assertEquals(DeploymentStatus.RUNNING, repository.deployments.value.single().status)

            harness.viewModel.onToggleSession(deploymentId)
            harness.awaitListRowStatus(deploymentId, DeploymentStatus.STOPPED)
        } finally {
            harness.closeE2EHarness()
            emulatorHarness.shutdownEmulatorHarness()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ib_engineLiquidity_noTradeOutcome_recordsClosedHistory() = runBlocking {
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

            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.STOPPED)
            val deployment = repository.deployments.value.single()
            E2EEngineLiquidityHelper.assertNoTradeOutcome(
                deployment,
                TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
            )
            assertTrue(deployment.sessionHistory.isNotEmpty())
            assertEquals(
                TouchTurnSessionStopTrigger.NO_TRADE_DECISION,
                deployment.sessionHistory.last().touchTurnRunRecord?.stopEvent?.stopTrigger
            )
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
