package daytrader.e2e

import daytrader.domain.TouchTurnSessionOutcome
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.ReplaySessionRunner
import daytrader.replay.SessionBundleLoader
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: captured session bundle replays through [ReplayHybridRuntime] + Touch Turn engine
 * and matches ground-truth outcome.
 */
class E2EReplayModeTest {
    @Test
    fun replaySession_fixtureBundle_matchesGroundTruthOutcomeAndFills() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
            val repository = InMemoryStrategyDeploymentRepository()
            val comparison = ReplaySessionRunner(bundle, repository, scope).run()

            assertTrue(
                comparison.outcomeMatches,
                "expected=${comparison.expectedOutcome} actual=${comparison.actualOutcome}"
            )
            assertEquals(
                TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                comparison.actualOutcome
            )

            val deployment = repository.deployments.value.single { it.id == bundle.deploymentId }
            val fillComparison = daytrader.replay.ReplayFillAssertions.compare(deployment, bundle)
            assertTrue(
                fillComparison.passed,
                "expectedFills=${fillComparison.expectedFillCount} actualFills=${fillComparison.actualFillCount}"
            )
        } finally {
            scope.cancel()
        }
    }
}
