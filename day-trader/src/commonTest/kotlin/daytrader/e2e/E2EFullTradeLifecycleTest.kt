package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEvent
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: liquidity-ready session → bracket entry fill → bracket exit → auto-stop with
 * closed session history and recorded PnL/trades.
 */
class E2EFullTradeLifecycleTest {
    @Test
    fun emulator_entryFill_exitFill_autoStopsWithClosedSessionHistory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = EmulatorModeTestHarness.fullTradeLifecycle(scope)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            seedLiquidityReadyDeployment(repository, deploymentId)

            val engine = harness.createEngine(repository)
            val sessionStoppedEvents = mutableListOf<TouchTurnEvent.SessionStopped>()
            engine.events
                .onEach { event ->
                    if (event is TouchTurnEvent.SessionStopped) {
                        sessionStoppedEvents += event
                    }
                }
                .launchIn(scope)

            harness.start()
            engine.start()
            harness.adapter.ensureStreamingMarketData(symbol)

            val plan = E2EBracketHelper.liquidityPlan(symbol = symbol)
            harness.gateway.placeTouchTurnBracket(plan)
            repository.update(deploymentId) { current ->
                current.withOrdersPlacedForSession(plan = plan)
            }

            awaitDeploymentStopped(engine, repository, deploymentId, harness, symbol, timeoutMs = 45_000)

            val stopped = repository.deployments.value.single { it.id == deploymentId }
            assertEquals(DeploymentStatus.STOPPED, stopped.status)

            val closedSession = stopped.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertTrue(closedSession.trades >= 1, "expected at least one round-trip trade")
            assertTrue(
                closedSession.sessionTrades.size >= 2,
                "expected entry and exit fills on closed session, had ${closedSession.sessionTrades.size}"
            )
            assertEquals(true, closedSession.positionOpened)
            assertEquals(true, closedSession.hadLiquidityCandle)
            assertEquals(true, closedSession.ordersPlacedForCandle)

            val runRecord = closedSession.touchTurnRunRecord
            assertNotNull(runRecord, "expected touchTurnRunRecord on closed session")
            assertEquals(
                TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                runRecord.stopEvent.stopTrigger
            )

            val stopEvent = sessionStoppedEvents.singleOrNull()
            assertNotNull(stopEvent, "expected TouchTurnEvent.SessionStopped from engine")
            assertEquals(TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN, stopEvent.trigger)
        } finally {
            scope.cancel()
        }
    }

    private fun seedLiquidityReadyDeployment(
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String
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

    private suspend fun awaitDeploymentStopped(
        engine: daytrader.engine.TouchTurnEnginePort,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String,
        harness: EmulatorModeTestHarness,
        symbol: String,
        timeoutMs: Long
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            engine.dispatch(TouchTurnCommand.PollStopRules)
            val status = repository.deployments.value.find { it.id == deploymentId }?.status
            if (status == DeploymentStatus.STOPPED) return
            delay(25)
        }
        val positions = harness.gateway.positions.value.filter { it.symbol == symbol.uppercase() }
        val fills = harness.gateway.fills.value.filter { it.symbol == symbol.uppercase() }
        val actual = repository.deployments.value.find { it.id == deploymentId }?.status
        error(
            "Timed out after ${timeoutMs}ms waiting for STOPPED; status=$actual " +
                "positions=$positions fillCount=${fills.size}"
        )
    }
}
