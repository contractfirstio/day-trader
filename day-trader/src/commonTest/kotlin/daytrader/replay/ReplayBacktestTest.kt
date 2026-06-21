package daytrader.replay

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorPricingSource
import daytrader.broker.emulator.TouchTurnEntryScenario
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ReplayCatalogTargetsTest {
    @Test
    fun resolve_prefersCatalogOverSeedPaths() {
        val catalog = listOf(
            ReplayCaptureRef("/a", "dep-a", "AAPL", "2026-06-04", 1L)
        )
        assertEquals(1, ReplayCatalogTargets.resolve(catalog, listOf("/b"), { Result.failure(Exception()) }).size)
    }

    @Test
    fun resolve_fallsBackToSeedPaths() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val refs = ReplayCatalogTargets.resolve(
            catalog = emptyList(),
            seedDirectoryPaths = listOf("/tmp/session"),
            loadBundle = { Result.success(bundle) }
        )
        assertEquals(1, refs.size)
        assertEquals("dep-replay-1", refs.single().deploymentId)
    }
}

class ReplayBacktestResultBuilderTest {
    @Test
    fun summarize_countsWinsLossesAndNoTrades() {
        val results = listOf(
            ReplayBacktestResult(
                deploymentId = "a",
                symbol = "A",
                sessionDate = "2026-06-04",
                captureDirectory = null,
                outcome = null,
                pnl = 10.0,
                roundTrips = 1,
                hasTangibleResult = true
            ),
            ReplayBacktestResult(
                deploymentId = "b",
                symbol = "B",
                sessionDate = "2026-06-04",
                captureDirectory = null,
                outcome = null,
                pnl = -5.0,
                roundTrips = 1,
                hasTangibleResult = true
            ),
            ReplayBacktestResult(
                deploymentId = "c",
                symbol = "C",
                sessionDate = "2026-06-04",
                captureDirectory = null,
                outcome = null,
                pnl = 0.0,
                roundTrips = 0,
                hasTangibleResult = true
            )
        )
        val summary = ReplayBacktestResultBuilder.summarize(results)
        assertEquals(1, summary.wins)
        assertEquals(1, summary.losses)
        assertEquals(1, summary.noTrades)
        assertEquals(5.0, summary.totalPnl)
        assertEquals(0.0, summary.originalTotalPnl)
        assertEquals(5.0, summary.totalPnlDelta)
        assertEquals(3, summary.tangibleResults)
    }
}

class ReplayBacktestPolicyTest {
    @Test
    fun rulesMatchGroundTruth_trueForTradeLifecycleFixture() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow()
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = bundle.symbol,
            maxDollars = bundle.groundTruth!!.runRecord.runContext.maxDollars,
        ).copy(
            id = bundle.deploymentId,
            touchTurnRules = ReplaySessionFixtures.tradeLifecycleRules(),
        )
        assertTrue(ReplayBacktestPolicy.rulesMatchGroundTruth(deployment, bundle))
        assertTrue(ReplayBacktestPolicy.useGroundTruthFills(deployment, bundle))
    }

    @Test
    fun rulesMatchGroundTruth_trueWhenRulesAndRiskUnchanged() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = bundle.symbol,
            maxDollars = 500
        ).copy(
            id = bundle.deploymentId,
            touchTurnRules = bundle.groundTruth!!.runRecord.rules!!
        )
        assertTrue(ReplayBacktestPolicy.rulesMatchGroundTruth(deployment, bundle))
    }
}

class BrokerEmulatorConfigReplayBacktestTest {
    @Test
    fun forReplayBacktest_fixesEntryScenarioAndNeutralizesRandomWalk() {
        val config = BrokerEmulatorConfig.forReplayBacktest()
        assertEquals(TouchTurnEntryScenario.APPROACH_AND_FILL, config.touchTurnEntryScenarioOverride)
        assertEquals(0.0, config.bracketWalkDirectionFlipChance)
        assertEquals(1.0, config.bracketWalkSteerTowardTargetProbability)
        assertEquals(EmulatorPricingSource.LIVE_EXCHANGE, config.pricingSource)
        assertEquals(true, config.flushEachExternalQuote)
    }
}

class BatchReplayOutcomeApplierTest {
    @Test
    fun mergeReplayOutcome_replacesClosedSessionsFromBaseline() {
        val sessionDate = "2026-06-04"
        val baseline = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "WDC",
            maxDollars = 500,
        ).copy(
            id = "dep-1",
            sessionHistory = listOf(
                StrategySession(
                    id = "old",
                    date = sessionDate,
                    pnl = 10.0,
                    trades = 1,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                )
            )
        )
        val replayed = baseline.copy(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(
                StrategySession(
                    id = "new",
                    date = sessionDate,
                    pnl = 67.31,
                    trades = 1,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                )
            )
        )
        val merged = BatchReplayOutcomeApplier.mergeReplayOutcome(baseline, replayed)
        assertEquals(1, merged.sessionHistory.size)
        assertEquals("new", merged.sessionHistory.single().id)
        assertEquals(67.31, merged.sessionHistory.single().pnl)
        assertEquals(DeploymentStatus.STOPPED, merged.status)
    }
}

class ReplayBacktestSessionTest {
    @Test
    fun runBacktestReplay_minimalFixture_producesTangibleNoTradeResult() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = InMemoryStrategyDeploymentRepository()
        val runtime = ReplayHybridRuntime(bundle, ReplayClock(bundle.timeline.sessionStartedEpochMs), scope)
        runtime.start()
        val engine = runtime.createEngine(repository)
        runtime.attachSessionEngine(engine)
        runtime.playbackOrchestrator.attach(engine, repository)
        engine.start()
        try {
            val controller = ReplaySessionController(runtime, repository, engine, scope)
            val result = controller.runBacktestReplay(bundle)
            assertTrue(result.result.hasTangibleResult, result.result.errorMessage)
            assertEquals(0.0, result.result.pnl)
            assertEquals(0, result.result.roundTrips)
            val deployment = repository.deployments.value.single { it.id == bundle.deploymentId }
            assertEquals(1, deployment.sessionHistory.size)
        } finally {
            engine.shutdown()
            runtime.shutdown()
        }
    }

    @Test
    fun runBacktestReplay_tradeLifecycleFixture_producesPositivePnl() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = InMemoryStrategyDeploymentRepository()
        repository.add(
            defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = bundle.symbol,
                maxDollars = bundle.groundTruth!!.runRecord.runContext.maxDollars,
            ).copy(
                id = bundle.deploymentId,
                touchTurnRules = ReplaySessionFixtures.tradeLifecycleRules(),
                status = DeploymentStatus.STOPPED,
            )
        )
        val runtime = ReplayHybridRuntime(bundle, ReplayClock(bundle.timeline.sessionStartedEpochMs), scope)
        runtime.start()
        runtime.registerBundle(bundle)
        val engine = runtime.createEngine(repository)
        runtime.attachSessionEngine(engine)
        runtime.playbackOrchestrator.attach(engine, repository)
        ReplaySessionPlaybackBridge(runtime.playbackOrchestrator, scope).attach(engine)
        engine.start()
        try {
            val controller = ReplaySessionController(runtime, repository, engine, scope)
            val run = controller.runBacktestReplay(bundle)
            assertTrue(run.result.hasTangibleResult, run.result.errorMessage)
            assertTrue(
                run.result.pnl > 0.0,
                "expected positive P&L, got ${run.result.pnl} outcome=${run.result.outcome}"
            )
        } finally {
            engine.shutdown()
            runtime.shutdown()
            scope.cancel()
        }
    }
}
