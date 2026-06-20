package daytrader.e2e

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
 * End-to-end bracket exit paths through emulator execution: take-profit, stop-loss, and trailing stop.
 */
class E2EBracketExitPathsTest {
    @Test
    fun emulator_takeProfitExit_autoStopsWithPositiveSessionPnl() = runBlocking {
        runExitScenario(
            harnessFactory = EmulatorModeTestHarness::fullTradeLifecycle,
            plan = E2EBracketHelper.liquidityPlan(),
        ) { closedSession ->
            assertTrue(
                closedSession.pnl > 0.0,
                "take-profit exit should produce positive session PnL"
            )
        }
    }

    @Test
    fun emulator_stopLossExit_autoStopsWithNegativeSessionPnl() = runBlocking {
        runExitScenario(
            harnessFactory = EmulatorModeTestHarness::stopLossLifecycle,
            plan = E2EBracketHelper.liquidityPlan(),
        ) { closedSession ->
            assertTrue(
                closedSession.pnl < 0.0,
                "stop-loss exit should produce negative session PnL"
            )
        }
    }

    @Test
    fun emulator_trailingStop_convertsToTrailBeforeTakeProfitExit() = runBlocking {
        var sawTrailOrder = false
        runExitScenario(
            harnessFactory = EmulatorModeTestHarness::trailingStopLifecycle,
            plan = E2EBracketHelper.trailingLiquidityPlan(),
            onPoll = { harness ->
                val hasTrail = harness.gateway.openOrders.value.any {
                    it.orderType.equals("TRAIL", ignoreCase = true)
                }
                if (hasTrail) sawTrailOrder = true
            }
        ) { closedSession ->
            assertTrue(sawTrailOrder, "expected stop to convert to TRAIL during favorable bracket walk")
            assertTrue(closedSession.trades >= 1)
            assertTrue(
                closedSession.pnl > 0.0,
                "trailing-stop path should still exit at take profit in this harness config"
            )
        }
    }

    private suspend fun runExitScenario(
        harnessFactory: (CoroutineScope) -> EmulatorModeTestHarness,
        plan: daytrader.domain.TouchTurnOrderPlan,
        onPoll: suspend (EmulatorModeTestHarness) -> Unit = {},
        assertClosedSession: (daytrader.domain.StrategySession) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = harnessFactory(scope)
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
                plan = plan,
                onPoll = { onPoll(harness) },
            )

            val stopped = repository.deployments.value.single { it.id == deploymentId }
            assertEquals(DeploymentStatus.STOPPED, stopped.status)

            val closedSession = stopped.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertTrue(closedSession.trades >= 1)
            assertTrue(closedSession.sessionTrades.size >= 2)
            assertEquals(true, closedSession.positionOpened)

            val runRecord = closedSession.touchTurnRunRecord
            assertNotNull(runRecord)
            assertEquals(TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN, runRecord.stopEvent.stopTrigger)

            assertClosedSession(closedSession)
        } finally {
            scope.cancel()
        }
    }
}
