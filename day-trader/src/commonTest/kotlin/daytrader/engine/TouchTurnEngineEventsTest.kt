package daytrader.engine

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.testTouchTurnEngine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

class TouchTurnEngineEventsTest {
    @Test
    fun sessionStarted_isDeliveredToMultipleCollectors() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        )
        repo.add(deployment)

        val engine = testTouchTurnEngine(gateway, repo, scope)
        val uiEvents = mutableListOf<TouchTurnEvent>()
        val replayEvents = mutableListOf<TouchTurnEvent>()
        engine.events.onEach { uiEvents += it }.launchIn(scope)
        engine.events.onEach { replayEvents += it }.launchIn(scope)

        engine.start()
        engine.dispatch(
            TouchTurnCommand.StartSession(
                instanceId = deployment.id,
                sessionDate = "2026-06-10"
            )
        )
        delay(50)

        assertTrue(uiEvents.any { it is TouchTurnEvent.SessionStarted })
        assertTrue(replayEvents.any { it is TouchTurnEvent.SessionStarted })
    }
}
