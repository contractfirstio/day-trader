package daytrader.engine

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

class TouchTurnEngineReplayTest {
    @Test
    fun startSession_blocksWhenReplayCaptureActivationFails() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "WDC",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        )
        repo.add(deployment)

        val engine = TouchTurnEngine(
            marketData = BrokerGatewayMarketDataProvider(gateway),
            execution = BrokerGatewayExecutionManager(gateway),
            repository = repo,
            scope = scope,
            brokerKind = BrokerKind.REPLAY,
            activateReplayCapture = { null },
            sessionGateway = gateway,
            executionGateway = gateway
        )
        val blocked = mutableListOf<TouchTurnEvent.StartBlocked>()
        engine.events.onEach { event ->
            if (event is TouchTurnEvent.StartBlocked) blocked += event
        }.launchIn(scope)
        engine.start()
        engine.dispatch(
            TouchTurnCommand.StartSession(
                instanceId = deployment.id,
                sessionDate = "2026-06-10"
            )
        )
        delay(50)
        val event = blocked.single()
        assertTrue(event.alert.summary.contains("WDC"))
        assertEquals(DeploymentStatus.STOPPED, repo.deployments.value.single().status)
    }

    @Test
    fun startSession_proceedsWhenReplayCaptureActivationSucceeds() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "WDC",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        )
        repo.add(deployment)

        val blocked = mutableListOf<TouchTurnEvent.StartBlocked>()
        val started = mutableListOf<TouchTurnEvent.SessionStarted>()
        val engine = TouchTurnEngine(
            marketData = BrokerGatewayMarketDataProvider(gateway),
            execution = BrokerGatewayExecutionManager(gateway),
            repository = repo,
            scope = scope,
            brokerKind = BrokerKind.REPLAY,
            activateReplayCapture = { "2026-06-10" },
            sessionGateway = gateway,
            executionGateway = gateway
        )
        engine.events.onEach { event ->
            when (event) {
                is TouchTurnEvent.StartBlocked -> blocked += event
                is TouchTurnEvent.SessionStarted -> started += event
                else -> Unit
            }
        }.launchIn(scope)
        engine.start()
        engine.dispatch(
            TouchTurnCommand.StartSession(
                instanceId = deployment.id,
                sessionDate = "2026-06-10"
            )
        )
        delay(50)
        assertTrue(blocked.isEmpty())
        assertTrue(started.isNotEmpty())
    }

    @Test
    fun startSession_usesCaptureSessionDateNotWallClockDate() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "WDC",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        )
        repo.add(deployment)

        val started = mutableListOf<TouchTurnEvent.SessionStarted>()
        val engine = TouchTurnEngine(
            marketData = BrokerGatewayMarketDataProvider(gateway),
            execution = BrokerGatewayExecutionManager(gateway),
            repository = repo,
            scope = scope,
            brokerKind = BrokerKind.REPLAY,
            activateReplayCapture = { "2026-06-12" },
            sessionGateway = gateway,
            executionGateway = gateway
        )
        engine.events.onEach { event ->
            if (event is TouchTurnEvent.SessionStarted) started += event
        }.launchIn(scope)
        engine.start()
        engine.dispatch(
            TouchTurnCommand.StartSession(
                instanceId = deployment.id,
                sessionDate = "2026-06-13"
            )
        )
        delay(50)
        assertEquals("2026-06-12", started.single().sessionDate)
    }
}
