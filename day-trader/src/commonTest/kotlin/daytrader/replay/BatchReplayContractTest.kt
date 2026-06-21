package daytrader.replay

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.BatchReplayTestHarness
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Contract tests for headless batch what-if replay.
 *
 * These are the pass/fail gate: if any test here fails, batch replay is not production-ready
 * regardless of other unit tests passing.
 */
class BatchReplayContractTest {
    @Test
    fun contract_threeCatalogEntries_produceThreeResults() = runBlocking {
        withHarness(
            bundles = listOf(
                tradeBundle(),
                minimalBundle(),
                tradeBundle(
                    deploymentId = "dep-batch-trade-2",
                    sessionId = "sess-batch-trade-2",
                    directorySuffix = "2",
                ),
            ),
        ) { harness, catalog ->
            harness.batchRunner.runCatalog(catalog)

            val diagnostics = assertNotNull(harness.batchRunner.lastRunDiagnostics)
            diagnostics.assertContract(expectedSessionCount = 3)
            assertEquals(3, harness.batchRunner.progress.value.summary?.results?.size)
        }
    }

    @Test
    fun contract_tradeCapture_hasPositivePnl_notDataFailed() = runBlocking {
        withHarness(bundles = listOf(tradeBundle())) { harness, catalog ->
            harness.batchRunner.runCatalog(catalog)

            val diagnostics = assertNotNull(harness.batchRunner.lastRunDiagnostics)
            diagnostics.assertContract(
                expectedSessionCount = 1,
                requirePositiveTradePnlForDeploymentIds = setOf(ReplaySessionFixtures.TRADE_DEPLOYMENT_ID),
            )
        }
    }

    /**
     * Headless batch replay should not rely on wall-clock sleeps per quote tick.
     * A single trade fixture must finish quickly; failure here indicates the async emulator
     * drain loops are still pacing on real time.
     */
    @Test
    fun contract_singleTradeCapture_completesWithinTimeBudget() = runBlocking {
        withHarness(bundles = listOf(tradeBundle())) { harness, catalog ->
            harness.batchRunner.runCatalog(catalog)

            val diagnostics = assertNotNull(harness.batchRunner.lastRunDiagnostics)
            diagnostics.assertContract(
                expectedSessionCount = 1,
                maxTotalElapsedMs = SINGLE_TRADE_TIME_BUDGET_MS,
                requirePositiveTradePnlForDeploymentIds = setOf(ReplaySessionFixtures.TRADE_DEPLOYMENT_ID),
            )
        }
    }

    @Test
    fun contract_catalogTargetsMatchBatchRunner_whenFilteredLikeDesktopPicker() {
        val fullCatalog = listOf(
            captureRef("/data/a", "dep-a"),
            captureRef("/data/b", "dep-b"),
            captureRef("/data/c", "dep-c"),
        )
        val pickedOnly = ReplayCatalogTargets.resolve(
            catalog = fullCatalog.filter { it.directoryPath == "/data/a" },
            seedDirectoryPaths = listOf("/data/a"),
            loadBundle = { Result.failure(IllegalStateException("unused")) },
        )
        assertEquals(1, pickedOnly.size, "desktop picker with one seed path yields one batch target")
    }

    private companion object {
        /** Wall-clock budget for one in-memory trade capture on CI hardware. */
        const val SINGLE_TRADE_TIME_BUDGET_MS = 8_000L

        fun captureRef(path: String, deploymentId: String): ReplayCaptureRef =
            ReplayCaptureRef(
                directoryPath = path,
                deploymentId = deploymentId,
                symbol = "AAPL",
                sessionDate = "2026-06-04",
                sessionStartedEpochMs = 1_780_579_800_000L,
            )

        fun tradeBundle(
            deploymentId: String = ReplaySessionFixtures.TRADE_DEPLOYMENT_ID,
            sessionId: String = "sess-batch-trade",
            directorySuffix: String = "",
        ) = SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow().copy(
            deploymentId = deploymentId,
            sessionId = sessionId,
        ).also { require(it.hasGroundTruth) }

        fun minimalBundle() =
            SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()

        fun seedDeployments(
            repository: daytrader.data.StrategyDeploymentRepository,
            bundles: List<daytrader.replay.SessionBundle>,
        ) {
            bundles.forEach { bundle ->
                val groundTruth = bundle.groundTruth ?: return@forEach
                repository.add(
                    defaultStrategyDeployment(
                        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                        symbol = bundle.symbol,
                        maxDollars = groundTruth.runRecord.runContext.maxDollars,
                    ).copy(
                        id = bundle.deploymentId,
                        touchTurnRules = when (bundle.deploymentId) {
                            ReplaySessionFixtures.TRADE_DEPLOYMENT_ID,
                            "dep-batch-trade-2" -> ReplaySessionFixtures.tradeLifecycleRules()
                            else -> groundTruth.runRecord.rules
                                ?: daytrader.domain.TouchTurnRuleConfig.defaultForBrokerKind(
                                    daytrader.gateway.BrokerKind.REPLAY
                                )
                        },
                        status = DeploymentStatus.STOPPED,
                    )
                )
            }
        }

        suspend fun withHarness(
            bundles: List<daytrader.replay.SessionBundle>,
            block: suspend (BatchReplayTestHarness, List<ReplayCaptureRef>) -> Unit,
        ) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var harness: BatchReplayTestHarness? = null
            try {
                val repository = daytrader.engine.support.InMemoryStrategyDeploymentRepository()
                seedDeployments(repository, bundles)
                harness = BatchReplayTestHarness.create(scope, repository, bundles)
                val catalog = bundles.map(BatchReplayTestHarness::captureRef)
                block(harness, catalog)
            } finally {
                harness?.shutdown()
                scope.cancel()
            }
        }
    }
}
