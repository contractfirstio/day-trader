package daytrader.e2e

import daytrader.domain.FiveMinuteConfirmationStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.TouchTurnMarketFixtures
import daytrader.e2e.support.TouchTurnMarketScenarioId
import daytrader.e2e.support.applyTo
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Programmatic mirror of IB @wip BDD expiry — kept for fast regression on the FakeBrokerGateway path. */
class E2EIbFiveMinuteExpiryTest {
    @E2EIbTest
    @Test
    fun ib_engineFiveMinuteConfirmation_expiresWithoutHammer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AtomicLong(E2ETestFixtures.BAR_CLOSE_EPOCH_MS)
        var harness: IbModeTestHarness? = null
        var engine: daytrader.engine.TouchTurnEngine? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val scenario = TouchTurnMarketFixtures.scenario(TouchTurnMarketScenarioId.RED_LIQUIDITY_LONG)
            harness = IbModeTestHarness()
            scenario.applyTo(harness.gateway)
            harness.gateway.fiveMinuteBarsFetchResult = Result.success(
                TouchTurnMarketFixtures.syntheticFiveMinuteBars(scenario, hammerBarIndex = -1)
            )
            assertTrue(harness.gateway.fiveMinuteBarsFetchResult.getOrThrow().isNotEmpty())

            repository.add(
                E2ETestFixtures.runningDeployment().copy(
                    touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                        enables = TouchTurnRuleEnables.DEFAULT.copy(
                            liquidityRangeDailyAtr = true,
                            fiveMinuteConfirmation = true,
                        )
                    )
                )
            )

            engine = harness.createEngine(repository, scope) { clock.get() }
            harness.start()
            E2EEngineLiquidityHelper.bootstrapAndAwaitLiquidity(
                engine = engine,
                repository = repository,
                startEngine = true,
                timeoutMs = 120_000,
                advanceTestClockWhenFiveMinuteAwaiting = { expiresAt -> clock.set(expiresAt + 1) },
            )

            val deployment = repository.deployments.value.single()
            val confirmation = deployment.touchTurnSession?.fiveMinuteConfirmation
                ?: deployment.sessionHistory.lastOrNull()?.touchTurnRunRecord?.fiveMinuteConfirmation
            assertNotNull(confirmation)
            assertEquals(FiveMinuteConfirmationStatus.EXPIRED, confirmation.status)
            assertEquals(
                TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_EXPIRED,
                E2EEngineLiquidityHelper.decisionOutcome(deployment)
            )
        } finally {
            engine.shutdownEngine()
            harness?.shutdown()
            scope.cancel()
        }
    }
}
