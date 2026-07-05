package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.e2e.support.shutdownIbHarness
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEvent
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerKind
import daytrader.gateway.BrokerId
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * End-to-end: Touch Turn engine [daytrader.engine.TouchTurnCommand.PollLiquidity] drives
 * liquidity evaluation and bracket submission — no manual repository shortcuts.
 */
class E2EEngineLiquidityEvaluationTest {
    @E2EIbTest
    @Test
    fun ib_enginePollLiquidity_nonLiquidityBar_autoStopsWithNoTradeDecision() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var ibHarness: IbModeTestHarness? = null
        var engine: TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.nonLiquidityOpeningBar()
            val gateway = FakeBrokerGateway(
                brokerId = BrokerId.INTERACTIVE_BROKERS,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar))
            )
            ibHarness = IbModeTestHarness(gateway)
            repository.add(E2EEngineLiquidityHelper.liquidityEnabledDeployment())

            engine = ibHarness.createEngine(repository, scope)
            val stopEvents = mutableListOf<TouchTurnEvent.SessionStopped>()
            engine.events
                .onEach { event ->
                    if (event is TouchTurnEvent.SessionStopped) stopEvents += event
                }
                .launchIn(scope)

            ibHarness.start()
            E2EEngineLiquidityHelper.bootstrapAndAwaitLiquidity(engine, repository)
            awaitDeploymentStopped(engine, repository)

            assertTrue(gateway.placedBrackets.isEmpty(), "engine must not place bracket for non-liquidity bar")

            val deployment = repository.deployments.value.single()
            E2EEngineLiquidityHelper.assertNoTradeOutcome(
                deployment,
                TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
            )
            assertEquals(DeploymentStatus.STOPPED, deployment.status)
            assertTrue(deployment.touchTurnSession == null, "stopped run should clear touchTurnSession")

            val stopEvent = stopEvents.singleOrNull()
            assertNotNull(stopEvent)
            assertEquals(TouchTurnSessionStopTrigger.NO_TRADE_DECISION, stopEvent.trigger)
        } finally {
            engine.shutdownEngine()
            ibHarness.shutdownIbHarness()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ib_enginePollLiquidity_liquidityBar_placesBracketViaEngine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var ibHarness: IbModeTestHarness? = null
        var engine: TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            val gateway = FakeBrokerGateway(
                brokerId = BrokerId.INTERACTIVE_BROKERS,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar))
            )
            ibHarness = IbModeTestHarness(gateway)
            repository.add(E2EEngineLiquidityHelper.liquidityEnabledDeployment())

            engine = ibHarness.createEngine(repository, scope)
            val bracketEvents = mutableListOf<TouchTurnEvent.BracketSubmitted>()
            engine.events
                .onEach { event ->
                    if (event is TouchTurnEvent.BracketSubmitted) bracketEvents += event
                }
                .launchIn(scope)

            ibHarness.start()
            E2EEngineLiquidityHelper.bootstrapAndAwaitLiquidity(engine, repository)

            val deployment = repository.deployments.value.single()
            E2EEngineLiquidityHelper.assertEngineEvaluatedLiquidity(deployment)
            val session = deployment.touchTurnSession
            assertNotNull(session?.setup, "liquidity bar must produce bracket setup")
            assertEquals(true, session.ordersPlacedForSession)
            assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, session.decisionOutcome)
            assertEquals(DeploymentStatus.RUNNING, deployment.status)

            assertEquals(1, gateway.placedBrackets.size, "engine must submit bracket to IB gateway")
            assertEquals(E2ETestFixtures.SYMBOL.uppercase(), gateway.placedBrackets.single().symbol)
            assertEquals(1, bracketEvents.size)
        } finally {
            engine.shutdownEngine()
            ibHarness.shutdownIbHarness()
            scope.cancel()
        }
    }

    @E2EEmulatorTest
    @Test
    fun emulatorBrokerKind_enginePollLiquidity_liquidityBar_placesBracketViaEngine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var engine: TouchTurnEngine? = null
        var gateway: FakeBrokerGateway? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            gateway = FakeBrokerGateway(
                brokerId = BrokerId.EMULATOR,
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar))
            )
            repository.add(E2EEngineLiquidityHelper.liquidityEnabledDeployment())

            engine = TouchTurnEngine(
                marketData = BrokerGatewayMarketDataProvider(gateway),
                execution = BrokerGatewayExecutionManager(gateway),
                repository = repository,
                scope = scope,
                brokerKind = BrokerKind.EMULATOR,
                nowEpochMillis = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
                sessionGateway = gateway,
                executionGateway = gateway
            )
            val bracketEvents = mutableListOf<TouchTurnEvent.BracketSubmitted>()
            engine.events
                .onEach { event ->
                    if (event is TouchTurnEvent.BracketSubmitted) bracketEvents += event
                }
                .launchIn(scope)

            gateway.connect()
            E2EEngineLiquidityHelper.bootstrapAndAwaitLiquidity(engine, repository)

            val deployment = repository.deployments.value.single()
            E2EEngineLiquidityHelper.assertEngineEvaluatedLiquidity(deployment)
            val session = deployment.touchTurnSession
            assertNotNull(session?.setup, "liquidity bar must produce bracket setup")
            assertEquals(true, session.ordersPlacedForSession)
            assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, session.decisionOutcome)
            assertEquals(DeploymentStatus.RUNNING, deployment.status)
            assertEquals(1, gateway.placedBrackets.size)
            assertEquals(1, bracketEvents.size)
        } finally {
            engine.shutdownEngine()
            gateway?.runCatching { disconnect() }
            scope.cancel()
        }
    }

    private suspend fun awaitDeploymentStopped(
        engine: TouchTurnEngine,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        timeoutMs: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            engine.drainUntilIdle(512)
            if (repository.deployments.value.find { it.id == deploymentId }?.status == DeploymentStatus.STOPPED) {
                return
            }
            delay(25)
        }
        error("Timed out waiting for deployment $deploymentId to reach STOPPED")
    }
}
