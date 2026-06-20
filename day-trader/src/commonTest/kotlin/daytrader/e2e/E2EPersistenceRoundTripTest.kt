package daytrader.e2e

import daytrader.data.persistence.DeploymentPersistence
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.e2e.support.E2EBracketExitHelper
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: after a live emulator session closes, deployment + session history survive
 * [DeploymentPersistence] record round-trip (disk format fidelity).
 */
class E2EPersistenceRoundTripTest {
    @Test
    fun emulator_closedSessionHistory_survivesDeploymentPersistenceRoundTrip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = EmulatorModeTestHarness.fullTradeLifecycle(scope)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            E2EBracketExitHelper.seedLiquidityReadyDeployment(repository, deploymentId)

            val engine = harness.createEngine(repository)
            E2EBracketExitHelper.runBracketExitCycle(
                engine = engine,
                repository = repository,
                harness = harness,
                deploymentId = deploymentId,
                symbol = symbol,
                plan = E2EBracketHelper.liquidityPlan(symbol = symbol),
            )

            val live = repository.deployments.value.single { it.id == deploymentId }
            assertEquals(DeploymentStatus.STOPPED, live.status)
            val closedSession = live.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertTrue(closedSession.trades >= 1)
            assertTrue(closedSession.pnl > 0.0)

            val record = DeploymentPersistence.toRecord(live)
            val restored = DeploymentPersistence.toDomain(record)

            assertEquals(live.id, restored.id)
            assertEquals(DeploymentStatus.STOPPED, restored.status)
            assertEquals(1, restored.sessionHistory.size)
            val restoredSession = restored.sessionHistory.single()
            assertEquals(closedSession.id, restoredSession.id)
            assertEquals(closedSession.pnl, restoredSession.pnl, 0.001)
            assertEquals(closedSession.trades, restoredSession.trades)
            assertEquals(closedSession.sessionTrades.size, restoredSession.sessionTrades.size)
            assertNotNull(restoredSession.touchTurnRunRecord)
            assertEquals(
                TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                restoredSession.touchTurnRunRecord?.stopEvent?.stopTrigger
            )
        } finally {
            scope.cancel()
        }
    }
}
