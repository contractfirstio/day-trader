package daytrader.e2e

import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnSessionDecision
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnStopEvent
import daytrader.e2e.support.E2ESessionRollupHelper
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.presentation.strategies.StrategyDetailTab
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: session history tab selection and recap panel through [StrategiesViewModel].
 */
class E2ESessionHistoryViewModelTest {
    @Test
    fun viewModel_selectSessionHistoryRow_marksSelectedAndShowsRecap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = E2EStrategiesViewModelHarness.create(
                scope,
                repository,
                FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            )
            val runId = "run-recap-1"
            val closedSession = E2ESessionRollupHelper.closedTradedSession(id = runId, pnl = 42.0).copy(
                touchTurnRunRecord = ReplaySessionFixtures.minimalRunRecord().copy(
                    decision = TouchTurnSessionDecision(
                        outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
                    ),
                    stopEvent = TouchTurnStopEvent(
                        stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN
                    )
                )
            )
            repository.add(
                E2ETestFixtures.stoppedDeployment().copy(
                    sessionHistory = listOf(closedSession)
                )
            )
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.SESSION_HISTORY)

            harness.viewModel.onSelectSessionHistory(runId)

            val history = harness.viewModel.detailState.value.sessionHistory
            assertNotNull(history)
            assertEquals(runId, history.selectedRunId)
            val row = history.rows.single { it.id == runId }
            assertTrue(row.isSelected)
            assertTrue(row.opensOnTradingTab)
            assertTrue(harness.viewModel.detailState.value.tradingPanelShowsSessionRecap)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewModel_sessionHistorySortByPnl_reordersRowsDescending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val harness = E2EStrategiesViewModelHarness.create(
                scope,
                repository,
                FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            )
            repository.add(
                E2ETestFixtures.stoppedDeployment().copy(
                    sessionHistory = listOf(
                        E2ESessionRollupHelper.closedTradedSession(
                            id = "run-low",
                            pnl = 10.0,
                            stoppedAt = "${E2ETestFixtures.SESSION_DATE}T09:45:00"
                        ),
                        E2ESessionRollupHelper.closedTradedSession(
                            id = "run-high",
                            pnl = 50.0,
                            stoppedAt = "${E2ETestFixtures.SESSION_DATE}T10:30:00"
                        )
                    )
                )
            )
            harness.selectDeployment(E2ETestFixtures.DEPLOYMENT_ID)
            harness.start()
            harness.viewModel.onDetailTabChange(StrategyDetailTab.SESSION_HISTORY)

            harness.viewModel.onSessionHistoryHeaderClick(
                daytrader.presentation.strategies.SessionHistorySortColumn.PNL
            )

            val rows = harness.viewModel.detailState.value.sessionHistory?.rows.orEmpty()
            assertEquals(2, rows.size)
            assertEquals("run-high", rows.first().id)
            assertEquals(SessionStatus.CLOSED, repository.deployments.value.single()
                .sessionHistory.first { it.id == rows.first().id }.status)
        } finally {
            scope.cancel()
        }
    }
}
