package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2EStartBlockedHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.TouchTurnEvent
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: capital-risk guard — starting a deployment while the broker already holds
 * an open position for that symbol fires [TouchTurnEvent.StartBlocked], surfaces the alert,
 * and does not submit orders.
 */
@E2EIbTest
class E2EStartBlockedByPositionTest {
    @Test
    fun viewModel_openPositionBlocksManualStart_emitsEventAndPlacesNoOrders() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            val blockedEvents = mutableListOf<TouchTurnEvent.StartBlocked>()
            val startedEvents = mutableListOf<TouchTurnEvent.SessionStarted>()
            harness.engine.events
                .onEach { event ->
                    when (event) {
                        is TouchTurnEvent.StartBlocked -> blockedEvents += event
                        is TouchTurnEvent.SessionStarted -> startedEvents += event
                        else -> Unit
                    }
                }
                .launchIn(scope)

            repository.add(E2ETestFixtures.stoppedDeployment())
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()
            gateway.setPositions(listOf(E2EStartBlockedHelper.openPosition()))
            harness.syncBrokerSnapshotToEngine()

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID)

            harness.awaitStartBlockedAlert()
            val alert = harness.viewModel.chromeState.value.startBlockedAlert
            assertNotNull(alert)
            assertEquals(E2ETestFixtures.SYMBOL, alert.instanceSymbol)
            assertNotNull(alert.position)
            assertTrue(alert.summary.contains("open position", ignoreCase = true))

            assertEquals(1, blockedEvents.size)
            assertEquals(E2ETestFixtures.SYMBOL, blockedEvents.single().alert.instanceSymbol)
            assertTrue(startedEvents.isEmpty(), "session must not start when blocked")
            assertTrue(gateway.placedBrackets.isEmpty(), "engine must not place bracket orders")
            assertTrue(gateway.flattenedSymbols.isEmpty(), "start-blocked must not flatten broker")

            val deployment = repository.deployments.value.single()
            assertEquals(DeploymentStatus.STOPPED, deployment.status)
            assertNull(deployment.touchTurnSession)
            assertTrue(deployment.sessionHistory.none { it.status == SessionStatus.IN_PROGRESS })
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_secondDeploymentOnSameSymbol_blockedWhileBrokerPositionOpen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.runningDeployment())
            repository.add(
                E2ETestFixtures.stoppedDeployment().copy(id = E2ETestFixtures.DEPLOYMENT_ID_2)
            )
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID_2)
            harness.start()
            gateway.setPositions(listOf(E2EStartBlockedHelper.openPosition()))
            harness.syncBrokerSnapshotToEngine()

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID_2)

            harness.awaitStartBlockedAlert()
            assertEquals(DeploymentStatus.RUNNING, repository.deployments.value.first { it.id == E2ETestFixtures.DEPLOYMENT_ID }.status)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.first { it.id == E2ETestFixtures.DEPLOYMENT_ID_2 }.status)
            assertTrue(gateway.placedBrackets.isEmpty())
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_hkSymbolAlias_openPositionBlocksStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            val deploymentId = "dep-hk-e2e"
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "700",
                    maxDollars = 500,
                    status = DeploymentStatus.STOPPED
                ).copy(id = deploymentId)
            )
            harness.selectDeployment(deploymentId)
            harness.start()
            gateway.setPositions(
                listOf(
                    E2EStartBlockedHelper.openPosition(symbol = "0700", quantity = 100).copy(
                        companyName = "Tencent",
                        currency = "HKD"
                    )
                )
            )
            harness.syncBrokerSnapshotToEngine()

            harness.viewModel.onToggleSession(deploymentId)

            harness.awaitStartBlockedAlert()
            val alert = harness.viewModel.chromeState.value.startBlockedAlert
            assertNotNull(alert)
            assertEquals("700", alert.instanceSymbol)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
            assertTrue(gateway.placedBrackets.isEmpty())
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }

    @Test
    fun viewModel_dismissStartBlockedAlert_clearsChromeState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.stoppedDeployment())
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()
            gateway.setPositions(listOf(E2EStartBlockedHelper.openPosition()))
            harness.syncBrokerSnapshotToEngine()

            harness.viewModel.onToggleSession(E2ETestFixtures.DEPLOYMENT_ID)
            harness.awaitStartBlockedAlert()
            assertNotNull(harness.viewModel.chromeState.value.startBlockedAlert)

            harness.viewModel.onDismissStartBlockedAlert()
            assertNull(harness.viewModel.chromeState.value.startBlockedAlert)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
