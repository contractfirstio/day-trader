package daytrader.replay

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.AppParityBatchReplayHarness
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.support.ReplaySessionFixtures
import daytrader.replay.support.SessionBundleTestWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Loads captures from disk via [SessionBundleDirectoryReader] with full AppDependencies engine wiring.
 * Closes the test/prod gap left by in-memory [daytrader.e2e.support.BatchReplayTestHarness].
 */
class BatchReplayAppParityTest {
    @Test
    fun appParity_diskLoadedTradeCapture_producesPositivePnlQuickly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeRoot = Files.createTempDirectory("batch-replay-app-parity")
        var harness: AppParityBatchReplayHarness? = null
        try {
            val contents = ReplaySessionFixtures.hybridTradeLifecycleContents()
            val sessionDir = SessionBundleTestWriter.writeSessionDirectory(
                scopeRoot = scopeRoot.resolve("paper-live-ib"),
                deploymentId = ReplaySessionFixtures.TRADE_DEPLOYMENT_ID,
                sessionId = "sess-batch-trade",
                contents = contents,
            )
            val bundle = SessionBundleDirectoryReader.loadReplayableFromDirectory(sessionDir.toString()).getOrThrow()
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
            harness = AppParityBatchReplayHarness.create(
                scope = scope,
                repository = repository,
                sessionDirectories = listOf(sessionDir),
            )

            harness.batchRunner.runCatalog(harness.replayCaptureCatalog)

            val diagnostics = assertNotNull(harness.batchRunner.lastRunDiagnostics)
            diagnostics.assertContract(
                expectedSessionCount = 1,
                maxTotalElapsedMs = DISK_LOAD_TIME_BUDGET_MS,
                requirePositiveTradePnlForDeploymentIds = setOf(ReplaySessionFixtures.TRADE_DEPLOYMENT_ID),
            )
        } finally {
            harness?.shutdown()
            scope.cancel()
            scopeRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun appParity_threeDiskCaptures_produceThreeResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeRoot = Files.createTempDirectory("batch-replay-app-parity-multi")
        var harness: AppParityBatchReplayHarness? = null
        try {
            val ibScope = scopeRoot.resolve("paper-live-ib")
            val tradeDir = SessionBundleTestWriter.writeSessionDirectory(
                scopeRoot = ibScope,
                deploymentId = ReplaySessionFixtures.TRADE_DEPLOYMENT_ID,
                sessionId = "sess-batch-trade",
                contents = ReplaySessionFixtures.hybridTradeLifecycleContents(),
            )
            val minimalDir = SessionBundleTestWriter.writeSessionDirectory(
                scopeRoot = ibScope,
                deploymentId = "dep-replay-1",
                sessionId = "sess-replay-1",
                contents = ReplaySessionFixtures.asHybridReplayable(ReplaySessionFixtures.minimalContents()),
            )
            val trade2Dir = SessionBundleTestWriter.writeSessionDirectory(
                scopeRoot = ibScope,
                deploymentId = "dep-batch-trade-2",
                sessionId = "sess-batch-trade-2",
                contents = ReplaySessionFixtures.hybridTradeLifecycleContents().let { contents ->
                    contents.copy(
                        manifestJson = contents.manifestJson
                            ?.replace(ReplaySessionFixtures.TRADE_DEPLOYMENT_ID, "dep-batch-trade-2")
                            ?.replace("sess-batch-trade", "sess-batch-trade-2"),
                        applicationJsonl = contents.applicationJsonl
                            .replace(ReplaySessionFixtures.TRADE_DEPLOYMENT_ID, "dep-batch-trade-2")
                            .replace("sess-batch-trade", "sess-batch-trade-2"),
                    )
                },
            )
            val repository = InMemoryStrategyDeploymentRepository()
            listOf(tradeDir, minimalDir, trade2Dir).forEach { dir ->
                val bundle = SessionBundleDirectoryReader.loadReplayableFromDirectory(dir.toString()).getOrThrow()
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
            harness = AppParityBatchReplayHarness.create(
                scope = scope,
                repository = repository,
                sessionDirectories = listOf(tradeDir, minimalDir, trade2Dir),
            )

            harness.batchRunner.runCatalog(harness.replayCaptureCatalog)

            val diagnostics = assertNotNull(harness.batchRunner.lastRunDiagnostics)
            diagnostics.assertContract(expectedSessionCount = 3)
        } finally {
            harness?.shutdown()
            scope.cancel()
            scopeRoot.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val DISK_LOAD_TIME_BUDGET_MS = 10_000L
    }
}
