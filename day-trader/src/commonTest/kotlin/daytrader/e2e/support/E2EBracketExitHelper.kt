package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withOrdersPlacedForSession
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.domain.TouchTurnOrderPlan
import kotlin.test.assertNotNull
import kotlinx.coroutines.delay

object E2EBracketExitHelper {
    fun seedLiquidityReadyDeployment(
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String,
    ) {
        val bar = E2ETestFixtures.redLiquidityOpeningBar()
        repository.update(deploymentId) { current ->
            val rules = (current.touchTurnRules ?: TouchTurnRuleConfig.DEFAULT).copy(
                enables = (current.touchTurnRules?.enables ?: TouchTurnRuleEnables.DEFAULT)
                    .copy(liquidityRangeDailyAtr = true)
            )
            current.copy(touchTurnRules = rules)
                .withFirstFifteenMinuteCandle(
                    sessionDate = E2ETestFixtures.SESSION_DATE,
                    candle = bar,
                    atr14 = E2ETestFixtures.ATR14,
                    dailyAtr14 = E2ETestFixtures.ATR14,
                    volumeSma20 = E2ETestFixtures.VOLUME_SMA20
                )
                .withOpeningBarClosedMilestone()
                .withClosedFirstFifteenMinuteCandle(bar)
                .withLiquidityEvaluatedIfClosed(
                    enforceCloseConfirmation = false,
                    nowEpochMillis = E2ETestFixtures.BAR_CLOSE_EPOCH_MS
                )
        }
        val setup = repository.deployments.value.single { it.id == deploymentId }.touchTurnSession?.setup
        assertNotNull(setup, "liquidity seed must produce bracket setup")
    }

    suspend fun runBracketExitCycle(
        engine: TouchTurnEnginePort,
        repository: InMemoryStrategyDeploymentRepository,
        harness: EmulatorModeTestHarness,
        deploymentId: String,
        symbol: String,
        plan: TouchTurnOrderPlan,
        timeoutMs: Long = 45_000,
        onPoll: suspend () -> Unit = {},
    ) {
        harness.start()
        engine.start()
        harness.adapter.ensureStreamingMarketData(symbol)

        harness.gateway.placeTouchTurnBracket(plan)
        repository.update(deploymentId) { current ->
            current.withOrdersPlacedForSession(plan = plan)
        }

        awaitDeploymentStopped(
            engine = engine,
            repository = repository,
            deploymentId = deploymentId,
            timeoutMs = timeoutMs,
            onPoll = onPoll,
        )
    }

    suspend fun awaitDeploymentStopped(
        engine: TouchTurnEnginePort,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String,
        timeoutMs: Long,
        onPoll: suspend () -> Unit = {},
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            engine.dispatch(TouchTurnCommand.PollStopRules)
            onPoll()
            val status = repository.deployments.value.find { it.id == deploymentId }?.status
            if (status == DeploymentStatus.STOPPED) return
            delay(25)
        }
        val actual = repository.deployments.value.find { it.id == deploymentId }?.status
        error("Timed out after ${timeoutMs}ms waiting for STOPPED; status=$actual")
    }
}
