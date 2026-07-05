package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlinx.coroutines.delay

class E2ESessionDriver(
    private val engine: TouchTurnEnginePort,
    private val repository: InMemoryStrategyDeploymentRepository
) {
    fun seedDeployment(deployment: StrategyDeployment = E2ETestFixtures.runningDeployment()) {
        repository.add(deployment)
    }

    /**
     * Starts the command loop. Bootstrap is driven explicitly via [loadFirstCandle]; the engine no
     * longer treats an already-connected gateway on subscribe as a reconnect event.
     */
    suspend fun startEngine() {
        engine.start()
    }

    suspend fun loadFirstCandle(
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        sessionDate: String = E2ETestFixtures.SESSION_DATE
    ) {
        val deployment = repository.deployments.value.firstOrNull { it.id == deploymentId }
        val session = deployment?.touchTurnSession
        when {
            deployment?.status == DeploymentStatus.STOPPED &&
                session?.status == TouchTurnCandleStatus.FAILED -> {
                repository.update(deploymentId) {
                    it.copy(status = DeploymentStatus.RUNNING).beginTouchTurnSession(sessionDate)
                }
                engine.dispatch(TouchTurnCommand.LoadFirstCandle(deploymentId, sessionDate))
            }
            session?.status != TouchTurnCandleStatus.READY -> {
                engine.dispatch(TouchTurnCommand.LoadFirstCandle(deploymentId, sessionDate))
            }
        }
        awaitCandleReady(deploymentId, timeoutMs = 30_000)
        delay(250)
    }

    suspend fun awaitLiquidityEvaluated(deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val session = repository.deployments.value
                .firstOrNull { it.id == deploymentId }
                ?.touchTurnSession
            if (session?.milestones?.liquidityEvaluatedAt != null ||
                session?.decisionOutcome != null ||
                session?.ordersPlacedForSession == true ||
                session?.setup != null ||
                session?.status == TouchTurnCandleStatus.FAILED
            ) {
                return
            }
            engine.dispatch(TouchTurnCommand.PollLiquidity(deploymentId))
            delay(500)
        }
        error("Timed out after ${timeoutMs}ms waiting for liquidity evaluation")
    }

    suspend fun stopSession(
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        trigger: TouchTurnSessionStopTrigger = TouchTurnSessionStopTrigger.MANUAL
    ) {
        engine.dispatch(TouchTurnCommand.StopSession(deploymentId, trigger))
        awaitDeploymentStopped(deploymentId)
    }

    fun deployment(id: String = E2ETestFixtures.DEPLOYMENT_ID): StrategyDeployment =
        repository.deployments.value.first { it.id == id }

    fun sessionOutcome(id: String = E2ETestFixtures.DEPLOYMENT_ID): TouchTurnSessionOutcome? =
        deployment(id).touchTurnSession?.decisionOutcome

    suspend fun awaitCandleReady(deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID, timeoutMs: Long = 10_000) {
        awaitCondition(timeoutMs) {
            val session = repository.deployments.value
                .firstOrNull { it.id == deploymentId }
                ?.touchTurnSession
            session?.status == TouchTurnCandleStatus.READY && session.atr14 != null
        }
    }

    suspend fun awaitDeploymentStopped(deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID, timeoutMs: Long = 5_000) {
        awaitCondition(timeoutMs) {
            repository.deployments.value
                .firstOrNull { it.id == deploymentId }
                ?.status == DeploymentStatus.STOPPED
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        error("Timed out after ${timeoutMs}ms waiting for condition")
    }
}
