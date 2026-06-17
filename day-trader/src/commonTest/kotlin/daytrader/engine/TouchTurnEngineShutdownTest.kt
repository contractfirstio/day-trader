package daytrader.engine

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnLogic
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.testTouchTurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class TouchTurnEngineShutdownTest {
    @Test
    fun shutdown_preventsAutoStartAfterEngineStopped() = runBlocking {
        val zone = "America/New_York"
        val sessionDate = "2026-05-22"
        val open = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zone, null)!!
        val now = open + 60_000L + 1

        val repo = InMemoryStrategyDeploymentRepository()
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).copy(autoStartOnMarketOpen = true)
        repo.add(instance)

        val engineJob = SupervisorJob()
        val scope = CoroutineScope(engineJob + Dispatchers.Default)
        val engine = testTouchTurnEngine(
            gateway = FakeBrokerGateway(),
            repository = repo,
            scope = scope,
            isGlobalAutoStartEnabled = { true },
            nowEpochMillis = { now }
        )
        engine.start()
        engine.shutdown()
        engineJob.cancel()

        engine.dispatch(TouchTurnCommand.EvaluateAutoStart)
        delay(300)

        assertEquals(DeploymentStatus.STOPPED, repo.deployments.value.first().status)
    }
}
