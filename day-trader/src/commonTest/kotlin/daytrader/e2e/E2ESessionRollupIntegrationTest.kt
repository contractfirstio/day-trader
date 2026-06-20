package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ESessionRollupHelper
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerId
import daytrader.presentation.strategies.StrategyDetailTab
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

/**
 * End-to-end: session close → [SessionRollupCache] invalidation → list row, filtered summary,
 * and session history rollup headers stay consistent (including after broker snapshot refresh).
 */
class E2ESessionRollupIntegrationTest {
    @Test
    fun viewModel_engineSessionClose_rollupsAgreeOnListSummaryAndHistory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val emulatorHarness = EmulatorModeTestHarness.fullTradeLifecycle(scope)
            val harness = E2EStrategiesViewModelHarness.createWithEmulator(scope, repository, emulatorHarness)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val symbol = E2ETestFixtures.SYMBOL

            repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
            seedLiquidityReadyDeployment(repository, deploymentId)
            harness.selectDeployment(deploymentId)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.LIVE)

            val plan = E2EBracketHelper.liquidityPlan(symbol = symbol)
            emulatorHarness.gateway.placeTouchTurnBracket(plan)
            repository.update(deploymentId) { current ->
                current.withOrdersPlacedForSession(plan = plan)
            }

            awaitDeploymentStopped(harness, repository, deploymentId, timeoutMs = 45_000)
            harness.awaitDetailTab(StrategyDetailTab.SESSION_HISTORY)
            harness.viewModel.onDetailTabChange(StrategyDetailTab.SESSION_HISTORY)

            val deployment = repository.deployments.value.single { it.id == deploymentId }
            val closedSession = deployment.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertTrue(closedSession.trades >= 1, "expected traded closed session")
            assertTrue(closedSession.pnl > 0.005, "expected winning session PnL, was ${closedSession.pnl}")
            assertEquals(
                TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                closedSession.touchTurnRunRecord?.stopEvent?.stopTrigger
            )

            val expected = E2ESessionRollupHelper.expectedRollupUi(deployment)
            harness.awaitListRowTotalPnL(deploymentId, expected.formattedTotalPnL)
            E2ESessionRollupHelper.assertRollupsConsistent(harness.viewModel, deploymentId, expected)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewModel_historyChangeThenBrokerTick_doesNotStaleRollupMetrics() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            val deploymentId = E2ETestFixtures.DEPLOYMENT_ID
            val priorSession = E2ESessionRollupHelper.closedTradedSession(id = "run-prior", pnl = 50.0)
            repository.add(
                E2ETestFixtures.stoppedDeployment().copy(sessionHistory = listOf(priorSession))
            )
            harness.selectDeployment(deploymentId)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.SESSION_HISTORY)

            val deploymentBefore = repository.deployments.value.single()
            val expectedBefore = E2ESessionRollupHelper.expectedRollupUi(deploymentBefore)
            harness.awaitListRowTotalPnL(deploymentId, expectedBefore.formattedTotalPnL)
            E2ESessionRollupHelper.assertRollupsConsistent(harness.viewModel, deploymentId, expectedBefore)

            val newSession = E2ESessionRollupHelper.closedTradedSession(
                id = "run-new",
                pnl = 20.0,
                stoppedAt = "${E2ETestFixtures.SESSION_DATE}T11:00:00"
            )
            repository.update(deploymentId) { current ->
                current.copy(sessionHistory = current.sessionHistory + newSession)
            }

            val deploymentAfter = repository.deployments.value.single()
            val expectedAfter = E2ESessionRollupHelper.expectedRollupUi(deploymentAfter)
            assertEquals("+$70.00", expectedAfter.formattedTotalPnL)
            harness.awaitListRowTotalPnL(deploymentId, expectedAfter.formattedTotalPnL)
            E2ESessionRollupHelper.assertRollupsConsistent(harness.viewModel, deploymentId, expectedAfter)

            gateway.setPositions(
                listOf(
                    AccountPosition(
                        account = "DU123",
                        symbol = E2ETestFixtures.SYMBOL,
                        companyName = "Apple Inc.",
                        quantity = 10,
                        avgPrice = 100.0,
                        marketPrice = 105.0,
                        priorClose = 99.0,
                        totalUnrealizedPnL = 50.0,
                        currency = "USD"
                    )
                )
            )
            delay(100)

            E2ESessionRollupHelper.assertRollupsConsistent(harness.viewModel, deploymentId, expectedAfter)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewModel_twoDeployments_summaryRollupsInvalidateOnHistoryChange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val harness = E2EStrategiesViewModelHarness.create(scope, repository, gateway)
            val dep1Id = E2ETestFixtures.DEPLOYMENT_ID
            val dep2Id = E2ETestFixtures.DEPLOYMENT_ID_2

            repository.add(E2ETestFixtures.stoppedDeployment(symbol = "AAPL"))
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "MSFT",
                    maxDollars = 500,
                    status = DeploymentStatus.STOPPED
                ).copy(
                    id = dep2Id,
                    sessionHistory = listOf(
                        E2ESessionRollupHelper.closedTradedSession(
                            id = "msft-run",
                            pnl = 50.0,
                            stoppedAt = "${E2ETestFixtures.SESSION_DATE}T09:45:00"
                        )
                    )
                )
            )
            harness.selectDeployment(dep1Id)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.SESSION_HISTORY)

            val newSession = E2ESessionRollupHelper.closedTradedSession(
                id = "aapl-run",
                pnl = 30.0,
                stoppedAt = "${E2ETestFixtures.SESSION_DATE}T10:30:00"
            )
            repository.update(dep1Id) { current ->
                current.copy(sessionHistory = listOf(newSession))
            }

            val allDeployments = repository.deployments.value
            val dep1 = allDeployments.single { it.id == dep1Id }
            val expectedDep1 = E2ESessionRollupHelper.expectedRollupUi(
                deployment = dep1,
                allDeployments = allDeployments
            )
            harness.awaitListRowTotalPnL(dep1Id, expectedDep1.formattedTotalPnL)
            assertEquals("+$80.00", expectedDep1.formattedNetPnL)
            assertEquals("+$80.00", expectedDep1.formattedSummaryLastSessionPnL)
            assertEquals("100%", expectedDep1.formattedSummaryWinRate)
            E2ESessionRollupHelper.assertRollupsConsistent(
                viewModel = harness.viewModel,
                deploymentId = dep1Id,
                expected = expectedDep1,
            )

            val summary = harness.viewModel.listState.value.filteredSummary
            assertNotNull(summary)
            assertEquals("+$80.00", summary.formattedNetPnL)
            assertEquals("100%", summary.formattedWinRate)
            assertEquals("+$80.00", summary.formattedLastSessionPnL)
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
    }

    private suspend fun awaitDeploymentStopped(
        harness: E2EStrategiesViewModelHarness,
        repository: InMemoryStrategyDeploymentRepository,
        deploymentId: String,
        timeoutMs: Long
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            harness.engine.dispatch(TouchTurnCommand.PollStopRules)
            val status = repository.deployments.value.find { it.id == deploymentId }?.status
            if (status == DeploymentStatus.STOPPED) return
            delay(25)
        }
        val actual = repository.deployments.value.find { it.id == deploymentId }?.status
        error("Timed out after ${timeoutMs}ms waiting for STOPPED; status=$actual")
    }
}
