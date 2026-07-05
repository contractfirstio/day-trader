package daytrader.engine

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.testTouchTurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Tier 2c: [TouchTurnEngine] closed-bar refetch failure branches. */
class TouchTurnEngineClosedBarRefetchTest {
    @Test
    fun closedBarRefetch_failure_marksDataFailedAndAutoStops() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            val gateway = FakeBrokerGateway(
                signalContextResult = Result.success(E2ETestFixtures.bootstrapContext(bar)),
            ).apply {
                closedBarRefetchResult = Result.failure(IllegalStateException("closed_bar_refetch_unavailable"))
            }
            repository.add(deploymentAwaitingClosedBarRefetch())

            val engine = testTouchTurnEngine(
                gateway = gateway,
                repository = repository,
                scope = scope,
                nowEpochMillis = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
            )
            try {
                engine.start()
                engine.dispatch(TouchTurnCommand.PollLiquidity(E2ETestFixtures.DEPLOYMENT_ID))
                awaitDataFailedStop(engine, repository)

                val deployment = repository.deployments.value.single()
                assertEquals(DeploymentStatus.STOPPED, deployment.status)
                assertEquals(
                    TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                    deployment.sessionHistory.lastOrNull()?.touchTurnRunRecord?.decision?.outcome,
                )
                val runRecord = deployment.sessionHistory.lastOrNull()?.touchTurnRunRecord
                assertNotNull(runRecord?.marketInputs?.dataErrorMessage)
                assertTrue(
                    runRecord!!.marketInputs.dataErrorMessage!!.contains("closed_bar_refetch_unavailable"),
                    "expected refetch error in session history but was ${runRecord.marketInputs.dataErrorMessage}",
                )
                assertTrue(gateway.placedBrackets.isEmpty())
            } finally {
                engine.shutdown()
                scope.cancel()
            }
        }
    }

    @Test
    fun closedBarRefetch_rejectedOhlc_marksDataFailedAndAutoStops() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repository = InMemoryStrategyDeploymentRepository()
            val bar = E2ETestFixtures.redLiquidityOpeningBar()
            val bootstrap = E2ETestFixtures.bootstrapContext(bar)
            val gateway = FakeBrokerGateway(
                signalContextResult = Result.success(bootstrap),
            ).apply {
                refetchSignalContexts = listOf(
                    bootstrap.copy(
                        firstCandle = bar.copy(high = 0.0, low = 0.0),
                    ),
                )
            }
            repository.add(deploymentAwaitingClosedBarRefetch())

            val engine = testTouchTurnEngine(
                gateway = gateway,
                repository = repository,
                scope = scope,
                nowEpochMillis = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
            )
            try {
                engine.start()
                engine.dispatch(TouchTurnCommand.PollLiquidity(E2ETestFixtures.DEPLOYMENT_ID))
                awaitDataFailedStop(engine, repository)

                val deployment = repository.deployments.value.single()
                assertEquals(DeploymentStatus.STOPPED, deployment.status)
                assertEquals(
                    TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                    deployment.sessionHistory.lastOrNull()?.touchTurnRunRecord?.decision?.outcome,
                )
            } finally {
                engine.shutdown()
                scope.cancel()
            }
        }
    }

    private suspend fun awaitDataFailedStop(
        engine: TouchTurnEngine,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        timeoutMs: Long = 15_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            engine.drainUntilIdle(512)
            val deployment = repository.deployments.value.find { it.id == deploymentId } ?: continue
            if (deployment.status == DeploymentStatus.STOPPED) {
                return
            }
            delay(25)
        }
        error("Timed out waiting for data-failed auto-stop on $deploymentId")
    }

    private fun deploymentAwaitingClosedBarRefetch() =
        E2ETestFixtures.runningDeployment().copy(
            touchTurnSession = E2ETestFixtures.runningDeployment().touchTurnSession!!.copy(
                candle = null,
                openingBarTime = E2ETestFixtures.redLiquidityOpeningBar().time,
                atr14 = E2ETestFixtures.ATR14,
                dailyAtr14 = E2ETestFixtures.ATR14,
                volumeSma20 = E2ETestFixtures.VOLUME_SMA20,
                adr14 = E2ETestFixtures.ATR14,
                status = TouchTurnCandleStatus.READY,
            ),
        )
}
