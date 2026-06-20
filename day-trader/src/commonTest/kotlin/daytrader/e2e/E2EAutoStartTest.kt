package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.e2e.support.E2EAutoStartHelper
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEvent
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.presentation.strategies.StrategyDetailTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: market-open auto-start through ViewModel + engine runtime.
 */
class E2EAutoStartTest {
    @Test
    fun viewModel_evaluateAutoStart_startsDeploymentAndUpdatesUiWithoutManualToggle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val sessionDate = "2026-06-04"
            val now = E2EAutoStartHelper.epochMillisAfterMarketOpenDelay(sessionDate)
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(
                scope = scope,
                repository = repository,
                gateway = gateway,
                nowEpochMillis = { now },
            )
            val startedEvents = mutableListOf<TouchTurnEvent.SessionStarted>()
            harness.engine.events
                .onEach { event ->
                    if (event is TouchTurnEvent.SessionStarted) startedEvents += event
                }
                .launchIn(scope)

            repository.add(E2EAutoStartHelper.autoStartEligibleDeployment(sessionDate))
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()

            harness.engine.dispatch(TouchTurnCommand.EvaluateAutoStart)
            delay(300)

            val deployment = repository.deployments.value.single()
            assertEquals(DeploymentStatus.RUNNING, deployment.status)
            assertEquals(sessionDate, deployment.lastAutoStartSessionDate)

            val startEvent = startedEvents.singleOrNull()
            assertTrue(startEvent != null, "expected SessionStarted from auto-start")
            assertEquals(TouchTurnSessionStartedBy.AUTO_MARKET_OPEN, startEvent.startedBy)

            harness.awaitListRowStatus(E2ETestFixtures.DEPLOYMENT_ID, DeploymentStatus.RUNNING)
            harness.awaitDetailTab(StrategyDetailTab.LIVE)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_globalAutoStartDisabled_doesNotAutoStartEligibleDeployment() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val sessionDate = "2026-06-04"
            val now = E2EAutoStartHelper.epochMillisAfterMarketOpenDelay(sessionDate)
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(
                scope = scope,
                repository = repository,
                gateway = gateway,
                nowEpochMillis = { now },
            )
            repository.add(E2EAutoStartHelper.autoStartEligibleDeployment(sessionDate))
            harness.start()

            harness.viewModel.onGlobalAutoStartEnabledChange(false)
            harness.engine.dispatch(TouchTurnCommand.EvaluateAutoStart)
            delay(300)

            val deployment = repository.deployments.value.single()
            assertEquals(DeploymentStatus.STOPPED, deployment.status)
            assertEquals(null, deployment.lastAutoStartSessionDate)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_lastAutoStartSessionDate_deduplicatesSameDayAutoStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val sessionDate = "2026-06-04"
            val now = E2EAutoStartHelper.epochMillisAfterMarketOpenDelay(sessionDate)
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(
                scope = scope,
                repository = repository,
                gateway = gateway,
                nowEpochMillis = { now },
            )
            repository.add(E2EAutoStartHelper.autoStartEligibleDeployment(sessionDate))
            harness.start()

            harness.engine.dispatch(TouchTurnCommand.EvaluateAutoStart)
            delay(300)
            assertEquals(DeploymentStatus.RUNNING, repository.deployments.value.single().status)

            harness.engine.dispatch(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.MANUAL
                )
            )
            delay(300)
            val stopped = repository.deployments.value.single()
            assertEquals(DeploymentStatus.STOPPED, stopped.status)
            assertEquals(sessionDate, stopped.lastAutoStartSessionDate)

            harness.engine.dispatch(TouchTurnCommand.EvaluateAutoStart)
            delay(300)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
