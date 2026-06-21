package daytrader.replay

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.BatchReplayTestHarness
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Performance contract: headless batch replay must stay fast at realistic quote volumes.
 * Fails when emulator drain loops use wall-clock sleeps per tick.
 */
class BatchReplayScaledQuoteTest {
    @Test
    fun contract_fiveThousandQuotes_completesWithinBudgetWithPositivePnl() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness: BatchReplayTestHarness? = null
        try {
            val tradeBundle = SessionBundleLoader
                .load(ReplaySessionFixtures.tradeLifecycleContents(totalQuoteCount = SCALED_QUOTE_COUNT))
                .getOrThrow()
            val repository = daytrader.engine.support.InMemoryStrategyDeploymentRepository()
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
            harness = BatchReplayTestHarness.createAppParity(scope, repository, listOf(tradeBundle))
            val catalog = listOf(BatchReplayTestHarness.captureRef(tradeBundle))

            harness.batchRunner.runCatalog(catalog)

            val diagnostics = assertNotNull(harness.batchRunner.lastRunDiagnostics)
            diagnostics.assertContract(
                expectedSessionCount = 1,
                maxTotalElapsedMs = SCALED_QUOTE_TIME_BUDGET_MS,
                requirePositiveTradePnlForDeploymentIds = setOf(ReplaySessionFixtures.TRADE_DEPLOYMENT_ID),
            )
        } finally {
            harness?.shutdown()
            scope.cancel()
        }
    }

    private companion object {
        const val SCALED_QUOTE_COUNT = 5_000
        const val SCALED_QUOTE_TIME_BUDGET_MS = 20_000L
    }
}
