package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngine
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
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

/** Tier 2c: manual session stop while bracket submission is still pending. */
class E2ETouchTurnSessionStopTest {
    @E2EEmulatorTest
    @Test
    fun emulator_manualStopWhileBracketAckPending_stopsWithoutOrdersPlaced() {
        runBlocking {
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
                engine.start()
                engine.dispatch(TouchTurnCommand.LoadFirstCandle(E2ETestFixtures.DEPLOYMENT_ID, E2ETestFixtures.SESSION_DATE))
                awaitBracketSubmitWithoutAck(engine, gateway)

                engine.dispatch(
                    TouchTurnCommand.StopSession(
                        E2ETestFixtures.DEPLOYMENT_ID,
                        TouchTurnSessionStopTrigger.MANUAL,
                    ),
                )
                engine.drainUntilIdle(512)
                delay(50)

                val deployment = repository.deployments.value.single()
                assertEquals(DeploymentStatus.STOPPED, deployment.status)
                assertFalse(deployment.touchTurnSession?.ordersPlacedForSession == true)
                assertEquals(1, gateway.placedBrackets.size, "broker still received bracket request before manual stop")
            } finally {
                engine.shutdownEngine()
                gateway?.runCatching { disconnect() }
                scope.cancel()
            }
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_manualStopWhileBracketAckPending_stopsWithoutOrdersPlaced() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var engine: TouchTurnEngine? = null
            var gateway: FakeBrokerGateway? = null
            try {
                val repository = InMemoryStrategyDeploymentRepository()
                val bar = E2ETestFixtures.redLiquidityOpeningBar()
                gateway = FakeBrokerGateway(
                    brokerId = BrokerId.INTERACTIVE_BROKERS,
                    signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar)),
                ).apply { deferBracketPlacementAck = true }
                repository.add(E2EEngineLiquidityHelper.liquidityEnabledDeployment())

                engine = TouchTurnEngine(
                    marketData = BrokerGatewayMarketDataProvider(gateway),
                    execution = BrokerGatewayExecutionManager(gateway),
                    repository = repository,
                    scope = scope,
                    brokerKind = BrokerKind.INTERACTIVE_BROKERS,
                    nowEpochMillis = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
                    sessionGateway = gateway,
                    executionGateway = gateway,
                )
                gateway.connect()
                engine.start()
                engine.dispatch(TouchTurnCommand.LoadFirstCandle(E2ETestFixtures.DEPLOYMENT_ID, E2ETestFixtures.SESSION_DATE))
                awaitBracketSubmitWithoutAck(engine, gateway)

                engine.dispatch(
                    TouchTurnCommand.StopSession(
                        E2ETestFixtures.DEPLOYMENT_ID,
                        TouchTurnSessionStopTrigger.MANUAL,
                    ),
                )
                engine.drainUntilIdle(512)
                delay(50)

                val deployment = repository.deployments.value.single()
                assertEquals(DeploymentStatus.STOPPED, deployment.status)
                assertFalse(deployment.touchTurnSession?.ordersPlacedForSession == true)
            } finally {
                engine.shutdownEngine()
                gateway?.runCatching { disconnect() }
                scope.cancel()
            }
        }
    }

    private suspend fun awaitBracketSubmitWithoutAck(
        engine: TouchTurnEngine,
        gateway: FakeBrokerGateway,
        timeoutMs: Long = 30_000,
    ) {
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
}
