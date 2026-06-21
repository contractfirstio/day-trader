package daytrader.replay

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.BatchReplayTestHarness
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Desktop batch replay is launched from Compose (Main). The engine command loop runs on
 * [Dispatchers.Default]. [BatchReplayRunner.runCatalog] must hop to the engine scope and use
 * sync backtest commands so every catalog entry runs — not just the first.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BatchReplayMainDispatcherContractTest {
    @Test
    fun contract_threeNoTradeCapturesFromUiThread_allProduceTangibleResults() = runBlocking {
        val bundles = listOf(
            tradeBundle(),
            minimalBundle("dep-ui-2", "sess-ui-2"),
            tradeBundle(
                deploymentId = "dep-ui-trade-3",
                sessionId = "sess-ui-trade-3",
            ),
        )
        runFromUiThread(bundles) { diagnostics ->
            diagnostics.assertContract(expectedSessionCount = 3)
            assertEquals(3, diagnostics.tangibleResults)
        }
    }

    @Test
    fun contract_fastNoTradeFromUiThread_producesTangibleBatchResult() = runBlocking {
        val bundle = minimalBundle("dep-ui-single", "sess-ui-single")
        runFromUiThread(listOf(bundle)) { diagnostics ->
            diagnostics.assertContract(expectedSessionCount = 1)
            val result = diagnostics.sessions.single()
            assertTrue(result.hasTangibleResult, result.errorMessage)
            assertEquals(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY, result.outcome)
        }
    }

    private suspend fun runFromUiThread(
        bundles: List<SessionBundle>,
        assertDiagnostics: (BatchReplayRunDiagnostics) -> Unit,
    ) {
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val uiContext = newSingleThreadContext("ui-thread")
        var harness: BatchReplayTestHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            bundles.forEach { bundle ->
                val groundTruth = bundle.groundTruth!!
                repository.add(
                    defaultStrategyDeployment(
                        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                        symbol = bundle.symbol,
                        maxDollars = groundTruth.runRecord.runContext.maxDollars,
                    ).copy(
                        id = bundle.deploymentId,
                        touchTurnRules = when (bundle.deploymentId) {
                            ReplaySessionFixtures.TRADE_DEPLOYMENT_ID,
                            "dep-ui-trade-3" -> ReplaySessionFixtures.tradeLifecycleRules()
                            else -> groundTruth.runRecord.rules!!
                        },
                        status = DeploymentStatus.STOPPED,
                    )
                )
            }
            harness = BatchReplayTestHarness.create(engineScope, repository, bundles)
            val catalog = bundles.map(BatchReplayTestHarness::captureRef)

            withContext(uiContext) {
                harness.batchRunner.runCatalog(catalog)
            }

            assertDiagnostics(assertNotNull(harness.batchRunner.lastRunDiagnostics))
        } finally {
            harness?.shutdown()
            engineScope.cancel()
            uiContext.close()
        }
    }

    private fun minimalBundle(deploymentId: String, sessionId: String): SessionBundle =
        SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow().copy(
            deploymentId = deploymentId,
            sessionId = sessionId,
        )

    private fun tradeBundle(
        deploymentId: String = ReplaySessionFixtures.TRADE_DEPLOYMENT_ID,
        sessionId: String = "sess-ui-trade",
    ): SessionBundle =
        SessionBundleLoader.load(ReplaySessionFixtures.tradeLifecycleContents()).getOrThrow().copy(
            deploymentId = deploymentId,
            sessionId = sessionId,
        )
}
