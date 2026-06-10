package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnCandleStatus
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

    suspend fun startEngine() {
        engine.start()
        engine.dispatch(TouchTurnCommand.BrokerConnected)
    }

    suspend fun loadFirstCandle(
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        sessionDate: String = E2ETestFixtures.SESSION_DATE
    ) {
        engine.dispatch(
            TouchTurnCommand.LoadFirstCandle(deploymentId, sessionDate)
        )
        awaitCandleReady(deploymentId)
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
