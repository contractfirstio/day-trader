package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.BrokerFaultInjector
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEmulatorHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Fault-injection E2E: broker disconnect/reconnect and orphan-order drift while sessions run.
 */
class E2EBrokerFaultInjectionTest {
    @E2EEmulatorTest
    @Test
    fun emulator_disconnectAfterBracketPlaced_reconnect_sessionStaysRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: EmulatorModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = EmulatorModeTestHarness.fullTradeLifecycle(scope)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            seedLiquidityReadyDeployment(repository, deploymentId)

            engine = harness.createEngine(repository)
            harness.start()
            engine.start()
            harness.adapter.ensureStreamingMarketData(symbol)

            val plan = E2EBracketHelper.liquidityPlan(symbol = symbol)
            harness.gateway.placeTouchTurnBracket(plan)
            repository.update(deploymentId) { current ->
                current.withOrdersPlacedForSession(plan = plan)
            }

            BrokerFaultInjector.disconnectReconnect(harness.gateway)
            delay(100)

            val deployment = repository.deployments.value.single { it.id == deploymentId }
            assertEquals(DeploymentStatus.RUNNING, deployment.status)
            assertTrue(deployment.touchTurnSession?.ordersPlacedForSession == true)
        } finally {
            engine.shutdownEngine()
            harness.shutdownEmulatorHarness()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_orphanOrdersBlockLiquidityBracketSubmit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: IbModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            harness = IbModeTestHarness()
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            seedLiquidityReadyDeployment(repository, deploymentId)

            engine = harness.createEngine(repository, scope)
            harness.start()
            engine.start()

            harness.gateway.setOpenOrders(listOf(BrokerFaultInjector.orphanLimitOrder(symbol)))
            engine.dispatch(TouchTurnCommand.PollLiquidity(deploymentId))
            delay(150)

            assertEquals(0, harness.gateway.placedBrackets.size)
            assertEquals(DeploymentStatus.RUNNING, repository.deployments.value.single().status)
        } finally {
            engine.shutdownEngine()
            harness?.shutdown()
            scope.cancel()
        }
    }

    private fun seedLiquidityReadyDeployment(
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
    }
}
