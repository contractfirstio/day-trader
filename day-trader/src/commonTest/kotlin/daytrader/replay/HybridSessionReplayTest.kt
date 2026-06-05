package daytrader.replay

import daytrader.domain.TouchTurnSessionOutcome
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class HybridSessionReplayTest {

    @Test
    fun replaySession_fixtureBundle_matchesGroundTruthOutcome() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = InMemoryStrategyDeploymentRepository()
        val comparison = ReplaySessionRunner(bundle, repository, scope).run()
        assertTrue(comparison.outcomeMatches, "expected=${comparison.expectedOutcome} actual=${comparison.actualOutcome}")
        val deployment = repository.deployments.value.single { it.id == bundle.deploymentId }
        val fillComparison = ReplayFillAssertions.compare(deployment, bundle)
        assertTrue(fillComparison.passed, "expectedFills=${fillComparison.expectedFillCount} actualFills=${fillComparison.actualFillCount}")
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            comparison.actualOutcome
        )
    }
}
