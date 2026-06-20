package daytrader.engine

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.testTouchTurnEngine
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class TouchTurnEngineBrokerReconnectTest {
    @Test
    fun brokerReconnect_retriesLoadingBootstrap() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING,
        ).beginTouchTurnSession("2026-06-10")
        deployment = deployment.copy(
            touchTurnSession = deployment.touchTurnSession!!.copy(status = TouchTurnCandleStatus.LOADING)
        )
        repo.add(deployment)

        val engine = testTouchTurnEngine(gateway, repo, scope)
        engine.start()

        gateway.disconnect()
        gateway.connect()
        delay(100)

        assertEquals(DeploymentStatus.RUNNING, repo.deployments.value.single().status)
    }

    @Test
    fun brokerSnapshot_withOrphanOrders_keepsSessionRunning() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING,
        ).beginTouchTurnSession("2026-06-10")
        repo.add(deployment)

        val engine = testTouchTurnEngine(gateway, repo, scope)
        engine.start()
        gateway.setOpenOrders(
            listOf(
                WorkingOrder(
                    orderId = 42,
                    symbol = "AAPL",
                    action = "BUY",
                    quantity = 10,
                    filled = 0,
                    remaining = 10,
                    orderType = "LMT",
                    limitPrice = 100.0,
                    stopPrice = null,
                    status = "Submitted",
                    currency = "USD",
                )
            )
        )
        delay(100)

        assertEquals(DeploymentStatus.RUNNING, repo.deployments.value.single().status)
    }

    @Test
    fun brokerReconnect_fromDisconnected_dispatchesConnectedHandling() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        gateway.disconnect()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING,
        ).beginTouchTurnSession("2026-06-10")
        repo.add(deployment)

        val engine = testTouchTurnEngine(gateway, repo, scope)
        engine.start()
        gateway.connect()
        delay(50)

        assertEquals(GatewayConnectionState.Connected, gateway.connectionState.value)
        assertEquals(DeploymentStatus.RUNNING, repo.deployments.value.single().status)
    }
}
