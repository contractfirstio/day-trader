package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlinx.coroutines.delay

/** Shared helpers for engine [TouchTurnCommand.PollLiquidity] E2E tests. */
object E2EEngineLiquidityHelper {
    fun liquidityEnabledDeployment(
        symbol: String = E2ETestFixtures.SYMBOL,
        sessionDate: String = E2ETestFixtures.SESSION_DATE
    ): StrategyDeployment =
        E2ETestFixtures.runningDeployment(symbol = symbol, sessionDate = sessionDate).copy(
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
            )
        )

    suspend fun bootstrapAndAwaitLiquidity(
        engine: TouchTurnEnginePort,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        sessionDate: String = E2ETestFixtures.SESSION_DATE,
        timeoutMs: Long = 30_000
    ) {
        engine.start()
        engine.dispatch(TouchTurnCommand.LoadFirstCandle(deploymentId, sessionDate))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val deployment = repository.deployments.value.find { it.id == deploymentId } ?: continue
            val session = deployment.touchTurnSession
            when {
                session?.ordersPlacedForSession == true -> return
                deployment.status == DeploymentStatus.STOPPED &&
                    deployment.sessionHistory.any { it.status == SessionStatus.CLOSED } -> return
            }
            delay(25)
        }
        val deployment = repository.deployments.value.find { it.id == deploymentId }
        val session = deployment?.touchTurnSession
        error(
            "Timed out after ${timeoutMs}ms waiting for engine liquidity evaluation; " +
                "status=${deployment?.status} " +
                "decisionOutcome=${session?.decisionOutcome} " +
                "ordersPlaced=${session?.ordersPlacedForSession} " +
                "liquidityEvaluatedAt=${session?.milestones?.liquidityEvaluatedAt} " +
                "closedSessions=${deployment?.sessionHistory?.count { it.status == SessionStatus.CLOSED }}"
        )
    }

    fun liquidityEvaluatedAt(deployment: StrategyDeployment): String? =
        deployment.touchTurnSession?.milestones?.liquidityEvaluatedAt
            ?: deployment.sessionHistory
                .filter { it.status == SessionStatus.CLOSED }
                .lastOrNull()
                ?.touchTurnMilestones
                ?.liquidityEvaluatedAt

    fun decisionOutcome(deployment: StrategyDeployment): TouchTurnSessionOutcome? =
        deployment.touchTurnSession?.decisionOutcome
            ?: deployment.sessionHistory
                .filter { it.status == SessionStatus.CLOSED }
                .lastOrNull()
                ?.touchTurnRunRecord
                ?.decision
                ?.outcome

    fun assertEngineEvaluatedLiquidity(deployment: StrategyDeployment) {
        require(liquidityEvaluatedAt(deployment) != null) {
            "expected engine to set liquidityEvaluatedAt milestone"
        }
    }

    fun assertNoTradeOutcome(deployment: StrategyDeployment, outcome: TouchTurnSessionOutcome) {
        assertEngineEvaluatedLiquidity(deployment)
        val actual = decisionOutcome(deployment)
        require(actual == outcome) {
            "expected $outcome but was $actual"
        }
    }
}
