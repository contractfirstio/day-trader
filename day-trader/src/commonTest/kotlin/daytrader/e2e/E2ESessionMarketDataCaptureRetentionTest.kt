package daytrader.e2e

import daytrader.data.LiveMarketDataLifecycle
import daytrader.data.SessionMarketDataCapture
import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.inProgressSession
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngine
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * IB session price capture must outlive early no-trade stops until the open + N minute cutoff
 * so replay has post-decision quotes for alternate rule scenarios.
 */
class E2ESessionMarketDataCaptureRetentionTest {
    @E2EIbTest
    @Test
    fun ibMode_noTradeStop_retainsPriceCapture_untilOpenDeadlineStop() = runBlocking {
        SessionMarketDataCapture.stopAll()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var engine: TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(E2ETestFixtures.stoppedDeployment())

            engine = TouchTurnEngine(
                marketData = BrokerGatewayMarketDataProvider(harness.gateway),
                execution = BrokerGatewayExecutionManager(harness.gateway),
                repository = repository,
                scope = scope,
                brokerKind = BrokerKind.INTERACTIVE_BROKERS,
                nowEpochMillis = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
                sessionGateway = harness.gateway,
                executionGateway = harness.gateway
            )
            engine.start()

            engine.dispatch(
                TouchTurnCommand.StartSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    sessionDate = E2ETestFixtures.SESSION_DATE
                )
            )
            delay(150)
            assertEquals(DeploymentStatus.RUNNING, repository.deployments.value.single().status)
            val sessionId = repository.deployments.value.single().inProgressSession()?.id
            assertNotNull(sessionId)
            assertEquals(
                sessionId,
                SessionMarketDataCapture.activeForDeployment(E2ETestFixtures.DEPLOYMENT_ID)?.sessionId
            )

            engine.dispatch(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.NO_TRADE_DECISION
                )
            )
            delay(150)

            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
            assertEquals(
                sessionId,
                SessionMarketDataCapture.activeForDeployment(E2ETestFixtures.DEPLOYMENT_ID)?.sessionId,
                "price capture must outlive NO_TRADE_DECISION for replay tape"
            )
            assertTrue(
                LiveMarketDataLifecycle.anyDeploymentNeedsQuotes(
                    E2ETestFixtures.SYMBOL,
                    repository.deployments.value
                )
            )

            engine.dispatch(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE
                )
            )
            delay(100)
            assertNull(SessionMarketDataCapture.activeForDeployment(E2ETestFixtures.DEPLOYMENT_ID))
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            SessionMarketDataCapture.stopAll()
            scope.cancel()
        }
    }
}
