package daytrader.engine

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnLogic
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MarketOpenAutoStarterTest {
    @Test
    fun evaluateAutoStart_startsEligibleDeploymentOnMarketOpen() = runBlocking {
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

        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = TouchTurnEngine(
            sessionGateway = gateway,
            executionGateway = gateway,
            repository = repo,
            scope = scope,
            isGlobalAutoStartEnabled = { true },
            nowEpochMillis = { now }
        )
        engine.start()
        engine.dispatch(TouchTurnCommand.EvaluateAutoStart)
        kotlinx.coroutines.delay(300)

        val updated = repo.deployments.value.first()
        assertEquals(DeploymentStatus.RUNNING, updated.status)
        assertEquals(sessionDate, updated.lastAutoStartSessionDate)
    }
}
