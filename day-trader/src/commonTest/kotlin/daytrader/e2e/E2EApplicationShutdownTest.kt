package daytrader.e2e

import daytrader.data.SessionMarketDataCapture
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.inProgressSession
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayCommand
import daytrader.ui.ApplicationQuitCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: application shutdown stops running sessions, flattens broker state,
 * releases session market-data capture, and persists deployment state.
 */
class E2EApplicationShutdownTest {
    @Test
    fun viewModel_applicationShutdown_flattensOpenPositionAndRecordsShutdownTrigger() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.runningDeployment())
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
            delay(100)

            harness.viewModel.shutdownRunningSessions()

            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
            assertTrue(
                gateway.flattenedSymbols.contains(E2ETestFixtures.SYMBOL.uppercase()),
                "expected broker flatten on application shutdown"
            )
            val closedSession = repository.deployments.value.single()
                .sessionHistory
                .single { it.status == SessionStatus.CLOSED }
            assertEquals(true, closedSession.positionOpened)
            val runRecord = closedSession.touchTurnRunRecord
            assertNotNull(runRecord)
            assertEquals(TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN, runRecord.stopEvent.stopTrigger)
            assertTrue(repository.flushInvocationCount >= 1, "expected persistence flush on shutdown")
            assertFalse(harness.viewModel.hasRunningSessions())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ib_applicationShutdown_releasesSessionMarketDataCapture() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SessionMarketDataCapture.stopAll()
            val repository = InMemoryStrategyDeploymentRepository()
            val releasedSymbols = mutableListOf<String>()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val harness = E2EStrategiesViewModelHarness.createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = BrokerKind.INTERACTIVE_BROKERS,
                releaseLiveMarketData = { symbol, _ -> releasedSymbols += symbol }
            )
            val deployment = E2ETestFixtures.runningDeployment()
            repository.add(deployment)
            val sessionId = deployment.inProgressSession()?.id ?: error("expected in-progress session")
            SessionMarketDataCapture.start(
                deploymentId = deployment.id,
                sessionId = sessionId,
                symbol = deployment.symbol,
                instrument = deployment.instrument
            )
            harness.start()
            assertTrue(harness.viewModel.hasActiveMarketDataCaptures())

            harness.viewModel.shutdownRunningSessions()

            assertFalse(harness.viewModel.hasActiveMarketDataCaptures())
            assertTrue(SessionMarketDataCapture.activeTargets().isEmpty())
            assertEquals(listOf(E2ETestFixtures.SYMBOL.uppercase()), releasedSymbols)
        } finally {
            SessionMarketDataCapture.stopAll()
            scope.cancel()
        }
    }

    @Test
    fun emulatorBrokerKind_applicationShutdown_flattensBrokerState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val harness = E2EStrategiesViewModelHarness.createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = BrokerKind.EMULATOR,
            )
            repository.add(E2ETestFixtures.runningDeployment())
            harness.start()

            harness.viewModel.shutdownRunningSessions()

            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
            assertTrue(
                gateway.flattenedSymbols.contains(E2ETestFixtures.SYMBOL.uppercase()),
                "expected emulator broker flatten on shutdown"
            )
            val runRecord = repository.deployments.value.single()
                .sessionHistory
                .single { it.status == SessionStatus.CLOSED }
                .touchTurnRunRecord
            assertNotNull(runRecord)
            assertEquals(TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN, runRecord.stopEvent.stopTrigger)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun applicationQuitSequence_stopsSessionsEngineAndBlocksFurtherEngineCommands() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            repository.add(E2ETestFixtures.runningDeployment())
            repository.add(
                E2ETestFixtures.runningDeployment(symbol = "MSFT").copy(id = E2ETestFixtures.DEPLOYMENT_ID_2)
            )
            harness.start()

            val quitCoordinator = ApplicationQuitCoordinator(
                hasRunningSessions = harness.viewModel::hasRunningSessions,
                runningSymbols = harness.viewModel::runningSessionSymbols,
                stopRunningSessions = harness.viewModel::shutdownRunningSessions,
                hasActiveMarketDataCaptures = harness.viewModel::hasActiveMarketDataCaptures,
                stopMarketDataCaptures = harness.viewModel::stopAllSessionMarketDataCaptures
            )
            assertTrue(quitCoordinator.hasRunningSessions())
            assertEquals(listOf("AAPL", "MSFT"), quitCoordinator.runningSymbols())

            quitCoordinator.stopRunningSessions()
            harness.engine.shutdown()

            assertFalse(quitCoordinator.hasRunningSessions())
            assertTrue(repository.deployments.value.all { it.status == DeploymentStatus.STOPPED })
            assertTrue(repository.flushInvocationCount >= 1)

            harness.engine.dispatch(
                TouchTurnCommand.StartSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    sessionDate = E2ETestFixtures.SESSION_DATE,
                    startedBy = TouchTurnSessionStartedBy.MANUAL
                )
            )
            delay(100)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.first().status)
        } finally {
            scope.cancel()
        }
    }
}
