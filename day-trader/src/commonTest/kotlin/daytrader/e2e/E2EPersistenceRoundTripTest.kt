package daytrader.e2e

import daytrader.data.persistence.DeploymentPersistence
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.e2e.support.E2EBracketExitHelper
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.shutdownEmulatorHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngine
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: after a live emulator session closes, deployment + session history survive
 * [DeploymentPersistence] record round-trip (disk format fidelity).
 */
@E2EEmulatorTest
class E2EPersistenceRoundTripTest {
    @Test
    fun emulator_closedSessionHistory_survivesDeploymentPersistenceRoundTrip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var emulatorHarness: EmulatorModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            emulatorHarness = EmulatorModeTestHarness.fullTradeLifecycle(scope)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            E2EBracketExitHelper.seedLiquidityReadyDeployment(repository, deploymentId)

            engine = emulatorHarness.createEngine(repository)
            E2EBracketExitHelper.runBracketExitCycle(
                engine = engine,
                repository = repository,
                harness = emulatorHarness,
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
            engine.shutdownEngine()
            emulatorHarness.shutdownEmulatorHarness()
            scope.cancel()
        }
    }

    @Test
    fun emulator_noTradeDataFailedSession_survivesDeploymentPersistenceRoundTrip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: TouchTurnEngine? = null
        var gateway: FakeBrokerGateway? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            gateway = FakeBrokerGateway(
                brokerId = BrokerId.EMULATOR,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar)),
            ).apply {
                closedBarRefetchResult = Result.failure(IllegalStateException("closed_bar_refetch_unavailable"))
            }
            repository.add(deploymentAwaitingClosedBarRefetch())

            engine = TouchTurnEngine(
                marketData = BrokerGatewayMarketDataProvider(gateway),
                execution = BrokerGatewayExecutionManager(gateway),
                repository = repository,
                scope = scope,
                brokerKind = BrokerKind.EMULATOR,
                nowEpochMillis = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
                sessionGateway = gateway,
                executionGateway = gateway,
            )
            gateway.connect()
            engine.start()
            engine.dispatch(TouchTurnCommand.PollLiquidity(E2ETestFixtures.DEPLOYMENT_ID))
            awaitDataFailedStop(engine, repository)

            val live = repository.deployments.value.single()
            assertEquals(DeploymentStatus.STOPPED, live.status)
            val closedSession = live.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertEquals(
                TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                closedSession.touchTurnRunRecord?.decision?.outcome,
            )

            val record = DeploymentPersistence.toRecord(live)
            val restored = DeploymentPersistence.toDomain(record)

            assertEquals(live.id, restored.id)
            assertEquals(DeploymentStatus.STOPPED, restored.status)
            val restoredSession = restored.sessionHistory.single()
            assertEquals(closedSession.id, restoredSession.id)
            assertEquals(
                TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                restoredSession.touchTurnRunRecord?.decision?.outcome,
            )
            assertTrue(
                restoredSession.touchTurnRunRecord?.marketInputs?.dataErrorMessage
                    ?.contains("closed_bar_refetch_unavailable") == true,
            )
        } finally {
            engine?.shutdown()
            gateway?.runCatching { disconnect() }
            scope.cancel()
        }
    }

    private suspend fun awaitDataFailedStop(
        engine: TouchTurnEngine,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        timeoutMs: Long = 15_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            engine.drainUntilIdle(512)
            if (repository.deployments.value.find { it.id == deploymentId }?.status == DeploymentStatus.STOPPED) {
                return
            }
            delay(25)
        }
        error("Timed out waiting for data-failed auto-stop on $deploymentId")
    }

    private fun deploymentAwaitingClosedBarRefetch() =
        E2ETestFixtures.runningDeployment().copy(
            touchTurnSession = E2ETestFixtures.runningDeployment().touchTurnSession!!.copy(
                candle = null,
                openingBarTime = E2ETestFixtures.redLiquidityOpeningBar().time,
                atr14 = E2ETestFixtures.ATR14,
                dailyAtr14 = E2ETestFixtures.ATR14,
                volumeSma20 = E2ETestFixtures.VOLUME_SMA20,
                adr14 = E2ETestFixtures.ATR14,
                status = TouchTurnCandleStatus.READY,
            ),
        )
}
