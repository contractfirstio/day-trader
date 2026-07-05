package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.BrokerFaultInjector
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEmulatorHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEnginePort
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Fault-injection E2E: broker disconnect/reconnect and orphan-order drift while sessions run.
 */
class E2EBrokerFaultInjectionTest {
    @E2EEmulatorTest
    @Test
    fun emulator_disconnectAfterBracketPlaced_reconnect_sessionStaysRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: EmulatorModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = EmulatorModeTestHarness.fullTradeLifecycle(scope)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            seedLiquidityReadyDeployment(repository, deploymentId)

            engine = harness.createEngine(repository)
            harness.start()
            engine.start()
            harness.adapter.ensureStreamingMarketData(symbol)

            val plan = E2EBracketHelper.liquidityPlan(symbol = symbol)
            harness.gateway.placeTouchTurnBracket(plan)
            repository.update(deploymentId) { current ->
                current.withOrdersPlacedForSession(plan = plan)
            }

            BrokerFaultInjector.disconnectReconnect(harness.gateway)
            delay(100)

            val deployment = repository.deployments.value.single { it.id == deploymentId }
            assertEquals(DeploymentStatus.RUNNING, deployment.status)
            assertTrue(deployment.touchTurnSession?.ordersPlacedForSession == true)
        } finally {
            engine.shutdownEngine()
            harness.shutdownEmulatorHarness()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_orphanOrdersBlockLiquidityBracketSubmit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: IbModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = IbModeTestHarness()
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            seedLiquidityReadyDeployment(repository, deploymentId)

            engine = harness.createEngine(repository, scope)
            harness.start()
            engine.start()

            harness.gateway.setOpenOrders(listOf(BrokerFaultInjector.orphanLimitOrder(symbol)))
            engine.dispatch(TouchTurnCommand.PollLiquidity(deploymentId))
            delay(150)

            assertEquals(0, harness.gateway.placedBrackets.size)
            assertEquals(DeploymentStatus.RUNNING, repository.deployments.value.single().status)
        } finally {
            engine.shutdownEngine()
            harness?.shutdown()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_disconnectWhileBracketAckPending_yieldsOrderRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: IbModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            val gateway = FakeBrokerGateway(
                brokerId = BrokerId.INTERACTIVE_BROKERS,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar)),
            ).apply { deferBracketPlacementAck = true }
            harness = IbModeTestHarness(gateway)
            repository.add(E2EEngineLiquidityHelper.liquidityEnabledDeployment())

            engine = harness.createEngine(repository, scope)
            harness.start()
            awaitEngineBracketSubmitWithoutAck(engine, gateway)

            assertEquals(1, gateway.placedBrackets.size)
            assertFalse(repository.deployments.value.single().touchTurnSession?.ordersPlacedForSession == true)

            gateway.disconnect()
            gateway.emitBracketAck(
                plan = gateway.placedBrackets.single(),
                result = Result.failure(IllegalStateException("ib_disconnect_during_bracket_ack")),
            )
            engine.drainUntilIdle(512)
            delay(50)

            val deployment = repository.deployments.value.single()
            assertEquals(
                TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED,
                E2EEngineLiquidityHelper.decisionOutcome(deployment),
            )
            assertEquals(DeploymentStatus.STOPPED, deployment.status)
        } finally {
            engine.shutdownEngine()
            harness?.shutdown()
            scope.cancel()
        }
    }

    @E2EEmulatorTest
    @Test
    fun emulator_disconnectWhileBracketAckPending_reconnectThenSuccessAck_placesBracket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: TouchTurnEngine? = null
        var gateway: FakeBrokerGateway? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            gateway = FakeBrokerGateway(
                brokerId = BrokerId.EMULATOR,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar)),
            ).apply { deferBracketPlacementAck = true }
            repository.add(E2EEngineLiquidityHelper.liquidityEnabledDeployment())

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
            awaitEngineBracketSubmitWithoutAck(engine, gateway)

            BrokerFaultInjector.disconnectReconnect(gateway)
            gateway.flushDeferredBracketAcks()
            engine.drainUntilIdle(512)
            withTimeout(15_000) {
                while (repository.deployments.value.single().touchTurnSession?.ordersPlacedForSession != true) {
                    engine.drainUntilIdle(64)
                    delay(25)
                }
            }

            val deployment = repository.deployments.value.single()
            assertTrue(deployment.touchTurnSession?.ordersPlacedForSession == true)
            assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, deployment.touchTurnSession?.decisionOutcome)
            assertEquals(DeploymentStatus.RUNNING, deployment.status)
        } finally {
            engine.shutdownEngine()
            gateway?.runCatching { disconnect() }
            scope.cancel()
        }
    }

    private suspend fun awaitEngineBracketSubmitWithoutAck(
        engine: TouchTurnEnginePort,
        gateway: FakeBrokerGateway,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        sessionDate: String = E2ETestFixtures.SESSION_DATE,
        timeoutMs: Long = 30_000,
    ) {
        engine.start()
        engine.dispatch(TouchTurnCommand.LoadFirstCandle(deploymentId, sessionDate))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (gateway.placedBrackets.isNotEmpty()) {
                engine.drainUntilIdle(512)
                return
            }
            engine.drainUntilIdle(64)
            delay(25)
        }
        error("engine never requested bracket placement within ${timeoutMs}ms")
    }

    private fun seedLiquidityReadyDeployment(
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String,
    ) {
        val bar = E2ETestFixtures.redLiquidityOpeningBar()
        repository.update(deploymentId) { current ->
            val rules = (current.touchTurnRules ?: TouchTurnRuleConfig.DEFAULT).copy(
                enables = (current.touchTurnRules?.enables ?: TouchTurnRuleEnables.DEFAULT)
                    .copy(liquidityRangeDailyAtr = true)
            )
            current.copy(touchTurnRules = rules)
                .withFirstFifteenMinuteCandle(
                    sessionDate = E2ETestFixtures.SESSION_DATE,
                    candle = bar,
                    atr14 = E2ETestFixtures.ATR14,
                    dailyAtr14 = E2ETestFixtures.ATR14,
                    volumeSma20 = E2ETestFixtures.VOLUME_SMA20
                )
                .withOpeningBarClosedMilestone()
                .withClosedFirstFifteenMinuteCandle(bar)
                .withLiquidityEvaluatedIfClosed(
                    enforceCloseConfirmation = false,
                    nowEpochMillis = E2ETestFixtures.BAR_CLOSE_EPOCH_MS
                )
        }
    }
}
