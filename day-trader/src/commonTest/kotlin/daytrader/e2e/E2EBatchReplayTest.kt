package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.StrategyType
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.e2e.support.BatchReplayTestHarness
import daytrader.replay.ReplayBacktestPolicy
import daytrader.replay.SessionBundleLoader
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
import kotlin.test.Ignore

/**
 * End-to-end: headless batch what-if replay must reproduce captured trade P&L from quotes,
 * not stop every session at $0 with [TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED].
 */
class E2EBatchReplayTest {
    @Test
    fun batchReplay_tradeCapture_producesNonZeroReplayPnl() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: BatchReplayTestHarness? = null
        try {
            val tradeBundle = SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = tradeBundle.symbol,
                    maxDollars = tradeBundle.groundTruth!!.runRecord.runContext.maxDollars,
                ).copy(
                    id = tradeBundle.deploymentId,
                    touchTurnRules = ReplaySessionFixtures.tradeLifecycleRules(),
                    status = DeploymentStatus.STOPPED,
                )
            )
            harness = BatchReplayTestHarness.create(
                scope = scope,
                repository = repository,
                bundles = listOf(tradeBundle),
            )
            val catalog = listOf(BatchReplayTestHarness.captureRef(tradeBundle))

            harness.batchRunner.runCatalog(catalog)

            val summary = harness.batchRunner.progress.value.summary
            assertNotNull(summary, "batch summary missing after runCatalog")
            val result = summary.results.single()
            assertTrue(result.hasTangibleResult, result.errorMessage ?: "expected tangible batch result")
            assertTrue(
                result.pnl > 0.0,
                "expected replay P&L > 0, got ${result.pnl} outcome=${result.outcome}"
            )
            assertEquals(1, result.roundTrips, "expected one round trip")
            assertEquals(1, summary.wins)
            assertTrue(summary.totalPnl > 0.0, "expected positive batch total P&L")

            val deployment = repository.deployments.value.single { it.id == tradeBundle.deploymentId }
            val closed = deployment.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertTrue(closed.trades >= 1, "expected trades on closed session")
            assertTrue(closed.sessionTrades.size >= 2, "expected entry and exit fills")
            assertEquals(
                TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                closed.touchTurnRunRecord?.stopEvent?.stopTrigger
            )
            assertTrue(
                closed.touchTurnRunRecord?.decision?.outcome != TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                "batch replay must not end in no_trade_data_failed"
            )
        } finally {
            harness?.shutdown()
            scope.cancel()
        }
    }

    @Test
    @Ignore
    fun batchReplay_tradeCapture_matchesOriginalCapturedPnlWhenRulesUnchanged() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: BatchReplayTestHarness? = null
        try {
            val tradeBundle = SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow()
            val originalPnl = ReplayBacktestPolicy.originalPnl(tradeBundle)
            assertTrue(originalPnl > 0.0, "fixture ground truth must have positive P&L")

            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = tradeBundle.symbol,
                    maxDollars = tradeBundle.groundTruth!!.runRecord.runContext.maxDollars,
                ).copy(
                    id = tradeBundle.deploymentId,
                    touchTurnRules = ReplaySessionFixtures.tradeLifecycleRules(),
                    status = DeploymentStatus.STOPPED,
                )
            )
            harness = BatchReplayTestHarness.create(scope, repository, listOf(tradeBundle))
            harness.batchRunner.runCatalog(listOf(BatchReplayTestHarness.captureRef(tradeBundle)))

            val result = harness.batchRunner.progress.value.summary!!.results.single()
            assertEquals(originalPnl, result.pnl, 0.01)
            assertEquals(0.0, result.pnlDelta, 0.01)
        } finally {
            harness?.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun batchReplay_withNewerCaptureInCatalog_replaysSelectedTradeCapture() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: BatchReplayTestHarness? = null
        try {
            val tradeBundle = SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow()
            val newerNoTradeBundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
            require(tradeBundle.symbol == newerNoTradeBundle.symbol) { "fixture symbols must match" }
            require(tradeBundle.deploymentId != newerNoTradeBundle.deploymentId) {
                "captures must use distinct deployment ids"
            }

            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = tradeBundle.symbol,
                    maxDollars = tradeBundle.groundTruth!!.runRecord.runContext.maxDollars,
                ).copy(
                    id = tradeBundle.deploymentId,
                    touchTurnRules = ReplaySessionFixtures.tradeLifecycleRules(),
                    status = DeploymentStatus.STOPPED,
                )
            )
            harness = BatchReplayTestHarness.create(
                scope = scope,
                repository = repository,
                bundles = listOf(tradeBundle, newerNoTradeBundle),
            )
            harness.batchRunner.runCatalog(listOf(BatchReplayTestHarness.captureRef(tradeBundle)))

            val result = harness.batchRunner.progress.value.summary!!.results.single()
            assertTrue(result.hasTangibleResult, result.errorMessage)
            assertTrue(result.pnl > 0.0, "expected trade capture P&L, got ${result.pnl}")
        } finally {
            harness?.shutdown()
            scope.cancel()
        }
    }
}
