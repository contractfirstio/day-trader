package daytrader.engine

import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.onSessionStarted
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class TouchTurnSessionBootstrapTest {
    @Test
    fun loadFirstCandle_fetchesAdrAndCandleThroughEngine() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(
            adrResult = Result.success(10.0),
            candleResult = Result.success(
                OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00")
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-22").beginTouchTurnSession("2026-05-22")
        repo.add(instance)

        val engine = TouchTurnEngine(
            sessionGateway = gateway,
            executionGateway = gateway,
            repository = repo,
            scope = scope
        )
        engine.start()
        engine.dispatch(TouchTurnCommand.LoadFirstCandle(instance.id, "2026-05-22"))
        var attempts = 0
        while (attempts < 100) {
            delay(50)
            val session = repo.deployments.value.first().touchTurnSession
            if (session?.candle != null && session.adr14 != null) break
            attempts++
        }

        val updated = repo.deployments.value.first()
        assertEquals(TouchTurnCandleStatus.READY, updated.touchTurnSession?.status)
        assertTrue(updated.touchTurnSession?.adr14 != null)
    }
}
