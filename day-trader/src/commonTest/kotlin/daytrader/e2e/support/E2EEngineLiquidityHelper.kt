package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.FiveMinuteConfirmationStatus
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
    private val fiveMinuteTerminalOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_EXPIRED,
        TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_INVALIDATED,
        TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_GROSS_PROFIT,
        TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_MISSED_TOUCH_TURN,
    )
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
        timeoutMs: Long = 30_000,
        startEngine: Boolean = true,
        advanceTestClockWhenFiveMinuteAwaiting: ((expiresAtEpochMs: Long) -> Unit)? = null,
    ) {
        if (startEngine) {
            engine.start()
        }
        engine.dispatch(TouchTurnCommand.LoadFirstCandle(deploymentId, sessionDate))
        var testClockAdvanced = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val deployment = repository.deployments.value.find { it.id == deploymentId } ?: continue
            val session = deployment.touchTurnSession
            val outcome = decisionOutcome(deployment) ?: session?.decisionOutcome

            if (advanceTestClockWhenFiveMinuteAwaiting != null && !testClockAdvanced) {
                val confirmation = session?.fiveMinuteConfirmation
                if (confirmation?.status == FiveMinuteConfirmationStatus.AWAITING) {
                    advanceTestClockWhenFiveMinuteAwaiting(confirmation.expiresAtEpochMs)
                    testClockAdvanced = true
                    engine.drainUntilIdle(512)
                }
            }

            when (session?.fiveMinuteConfirmation?.status) {
                FiveMinuteConfirmationStatus.EXPIRED,
                FiveMinuteConfirmationStatus.INVALIDATED,
                FiveMinuteConfirmationStatus.REJECTED_INSUFFICIENT_GROSS_PROFIT,
                FiveMinuteConfirmationStatus.REJECTED_MISSED_TOUCH_TURN -> {
                    engine.drainUntilIdle()
                    return
                }
                else -> Unit
            }

            when {
                session?.ordersPlacedForSession == true -> {
                    engine.drainUntilIdle()
                    return
                }
                outcome == TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED -> {
                    engine.drainUntilIdle()
                    return
                }
                outcome in fiveMinuteTerminalOutcomes -> {
                    engine.drainUntilIdle()
                    return
                }
                liquidityEvaluatedAt(deployment) != null &&
                    decisionOutcome(deployment) != null &&
                    session?.ordersPlacedForSession != true -> {
                    engine.drainUntilIdle()
                    return
                }
                deployment.status == DeploymentStatus.STOPPED &&
                    deployment.sessionHistory.any { it.status == SessionStatus.CLOSED } -> {
                    engine.drainUntilIdle()
                    return
                }
            }

            engine.drainUntilIdle()
            delay(25)
        }
        val deployment = repository.deployments.value.find { it.id == deploymentId }
        val session = deployment?.touchTurnSession
        error(
            "Timed out after ${timeoutMs}ms waiting for engine liquidity evaluation; " +
                "status=${deployment?.status} " +
                "decisionOutcome=${session?.decisionOutcome ?: deployment?.let(::decisionOutcome)} " +
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
