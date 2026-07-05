package daytrader.engine

import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.beginTouchTurnSession
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.testTouchTurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class TouchTurnSessionBootstrapTest {
    @Test
    fun loadFirstCandle_fetchesAdrAndCandleThroughEngine() = runBlocking {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00")
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(
            signalContextResult = Result.success(
                TouchTurnSignalContext(
                    firstCandle = bar,
                    atr14 = 10.0,
                    volumeSma20 = 30_000.0,
                )
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED,
        ).beginTouchTurnSession("2026-05-22")
        repo.add(instance)

        val engine = testTouchTurnEngine(gateway, repo, scope)
        try {
            engine.start()
            engine.dispatch(TouchTurnCommand.LoadFirstCandle(instance.id, "2026-05-22"))
            awaitBootstrapReady(repo)

            val updated = repo.deployments.value.first()
            assertEquals(TouchTurnCandleStatus.READY, updated.touchTurnSession?.status)
            assertEquals(bar.time, updated.touchTurnSession?.openingBarTime)
            assertEquals(10.0, updated.touchTurnSession?.atr14)
        } finally {
            engine.shutdown()
            scope.cancel()
        }
    }

    private suspend fun awaitBootstrapReady(repo: InMemoryStrategyDeploymentRepository) {
        repeat(512) {
            if (repo.deployments.value.firstOrNull()?.touchTurnSession?.status == TouchTurnCandleStatus.READY) {
                return
            }
            yield()
        }
        error("bootstrap did not reach READY")
    }
}
