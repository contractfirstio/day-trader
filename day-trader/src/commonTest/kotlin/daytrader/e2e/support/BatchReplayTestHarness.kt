package daytrader.e2e.support

import daytrader.data.StrategyDeploymentRepository
import daytrader.diagnostics.SessionTrace
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEnginePort
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.execution.ExecutionManager
import daytrader.execution.LoggingExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.replay.BatchReplayRunner
import daytrader.replay.ReplayBundleResolver
import daytrader.replay.ReplayCaptureRef
import daytrader.replay.ReplayClock
import daytrader.replay.ReplayHybridRuntime
import daytrader.replay.ReplaySessionController
import daytrader.replay.ReplaySessionPlaybackBridge
import daytrader.replay.ReplaySessionTiming
import daytrader.replay.SessionBundle
import kotlinx.coroutines.CoroutineScope

/**
 * Production-like replay wiring for batch what-if E2E tests: hybrid runtime, playback bridge,
 * and [BatchReplayRunner] with in-memory bundle loading.
 *
 * Use [createAppParity] to mirror desktop [daytrader.ui.AppDependencies] engine wiring
 * (LoggingExecutionManager, liquidity buckets, SessionTrace on capture activation).
 */
class BatchReplayTestHarness private constructor(
    val repository: StrategyDeploymentRepository,
    val runtime: ReplayHybridRuntime,
    val engine: TouchTurnEnginePort,
    val controller: ReplaySessionController,
    val batchRunner: BatchReplayRunner,
    private val bundlesByPath: Map<String, SessionBundle>,
    val replayCaptureCatalog: List<ReplayCaptureRef>,
    private val globalAutoStartEnabled: () -> Boolean,
) {
    fun loadBundle(path: String): Result<SessionBundle> =
        bundlesByPath[path]?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("Unknown capture path: $path"))

    fun shutdown() {
        engine.shutdown()
        runtime.shutdown()
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            repository: StrategyDeploymentRepository,
            bundles: List<SessionBundle>,
        ): BatchReplayTestHarness = createInternal(
            scope = scope,
            repository = repository,
            bundles = bundles,
            appParity = false,
            globalAutoStartEnabled = { false },
        )

        fun createAppParity(
            scope: CoroutineScope,
            repository: StrategyDeploymentRepository,
            bundles: List<SessionBundle>,
            globalAutoStartEnabled: () -> Boolean = { false },
        ): BatchReplayTestHarness = createInternal(
            scope = scope,
            repository = repository,
            bundles = bundles,
            appParity = true,
            globalAutoStartEnabled = globalAutoStartEnabled,
        )

        fun captureRef(bundle: SessionBundle): ReplayCaptureRef =
            ReplayCaptureRef(
                directoryPath = bundlePath(bundle),
                deploymentId = bundle.deploymentId,
                symbol = bundle.symbol,
                sessionDate = bundle.sessionDate,
                sessionStartedEpochMs = bundle.timeline.sessionStartedEpochMs
            )

        fun bundlePath(bundle: SessionBundle): String =
            "/tmp/replay-batch/${bundle.deploymentId}/${bundle.sessionId}"

        private fun createInternal(
            scope: CoroutineScope,
            repository: StrategyDeploymentRepository,
            bundles: List<SessionBundle>,
            appParity: Boolean,
            globalAutoStartEnabled: () -> Boolean,
        ): BatchReplayTestHarness {
            require(bundles.isNotEmpty()) { "At least one bundle required" }
            val bundlesByPath = bundles.associateBy { bundlePath(it) }
            val primary = bundles.first()
            val clock = ReplayClock(primary.timeline.sessionStartedEpochMs)
            val runtime = ReplayHybridRuntime(primary, clock, scope)
            runtime.start()
            bundles.forEach { runtime.registerBundle(it) }
            val catalog = bundles.map { captureRef(it) }
            val liquidityBucketRepository = if (appParity) InMemoryLiquidityBucketRepository() else null
            val engine = if (appParity) {
                createEngine(
                    runtime = runtime,
                    clock = clock,
                    repository = repository,
                    scope = scope,
                    catalog = catalog,
                    bundlesByPath = bundlesByPath,
                    appParity = true,
                    globalAutoStartEnabled = globalAutoStartEnabled,
                    liquidityBucketRepository = liquidityBucketRepository!!,
                )
            } else {
                createEngine(
                    runtime = runtime,
                    clock = clock,
                    repository = repository,
                    scope = scope,
                    catalog = catalog,
                    bundlesByPath = bundlesByPath,
                    appParity = false,
                    globalAutoStartEnabled = globalAutoStartEnabled,
                    liquidityBucketRepository = null,
                )
            }
            runtime.attachSessionEngine(engine)
            runtime.playbackOrchestrator.attach(engine, repository)
            ReplaySessionPlaybackBridge(runtime.playbackOrchestrator, scope).attach(engine)
            engine.start()
            val controller = ReplaySessionController(runtime, repository, engine, scope)
            val batchRunner = BatchReplayRunner(
                controller = controller,
                repository = repository,
                loadBundle = { path ->
                    bundlesByPath[path]?.let { Result.success(it) }
                        ?: Result.failure(IllegalArgumentException("Unknown capture: $path"))
                },
                restoreEngineGlobalAutoStart = globalAutoStartEnabled,
            )
            return BatchReplayTestHarness(
                repository = repository,
                runtime = runtime,
                engine = engine,
                controller = controller,
                batchRunner = batchRunner,
                bundlesByPath = bundlesByPath,
                replayCaptureCatalog = catalog,
                globalAutoStartEnabled = globalAutoStartEnabled,
            )
        }

        private fun createEngine(
            runtime: ReplayHybridRuntime,
            clock: ReplayClock,
            repository: StrategyDeploymentRepository,
            scope: CoroutineScope,
            catalog: List<ReplayCaptureRef>,
            bundlesByPath: Map<String, SessionBundle>,
            appParity: Boolean,
            globalAutoStartEnabled: () -> Boolean,
            liquidityBucketRepository: InMemoryLiquidityBucketRepository?,
        ): TouchTurnEnginePort {
            val sessionGateway = runtime.marketDataGateway
            val executionGateway = runtime.executionGateway
            val marketData = BrokerGatewayMarketDataProvider(
                gateway = sessionGateway,
                ensureLiveMarketData = { symbol, _ -> runtime.ensureStreamingMarketData(symbol) },
                releaseLiveMarketData = { symbol, _ -> runtime.releaseStreamingMarketData(symbol) },
            )
            val baseExecution = BrokerGatewayExecutionManager(executionGateway)
            val execution: ExecutionManager = if (appParity && executionGateway.brokerId != BrokerId.INTERACTIVE_BROKERS) {
                LoggingExecutionManager(baseExecution, executionGateway.brokerId)
            } else {
                baseExecution
            }
            val activateReplayCapture: (StrategyDeployment) -> String? = { deployment ->
                val headlessDate = runtime.headlessBacktestSessionDate(deployment.id)
                if (headlessDate != null) {
                    headlessDate
                } else {
                    val capture = ReplayBundleResolver.selectCapture(deployment, catalog)
                    val bundle = capture?.directoryPath?.let { bundlesByPath[it] }
                    if (capture == null || bundle == null) {
                        null
                    } else {
                        if (appParity) {
                            val previous = runtime.captureRegistry.bundleFor(deployment.symbol)?.sessionId
                            SessionTrace.log(
                                type = "replay_capture_activated",
                                deploymentId = deployment.id,
                                symbol = deployment.symbol,
                                details = mapOf(
                                    "captureDirectory" to capture.directoryPath,
                                    "captureSessionId" to bundle.sessionId,
                                    "previousSessionId" to (previous ?: "null"),
                                    "captureSessionDate" to (bundle.sessionDate ?: "null"),
                                ),
                            )
                        }
                        runtime.registerBundle(bundle)
                        ReplaySessionController.seedDeploymentIfNeeded(repository, bundle)
                        bundle.sessionDate
                    }
                }
            }
            return TouchTurnEngine(
                marketData = marketData,
                execution = execution,
                repository = repository,
                scope = scope,
                brokerKind = BrokerKind.REPLAY,
                isGlobalAutoStartEnabled = globalAutoStartEnabled,
                nowEpochMillis = clock::nowEpochMillis,
                delayMillis = clock::delayMillis,
                onReplaySessionStarting = { deployment, sessionDate ->
                    if (runtime.headlessBacktestDeploymentId != deployment.id) {
                        val othersRunning = repository.deployments.value.any {
                            it.status == DeploymentStatus.RUNNING && it.id != deployment.id
                        }
                        if (!othersRunning) {
                            ReplaySessionTiming.alignClockToSessionOpen(clock, deployment, sessionDate)
                        }
                        runtime.prepareForSession(deployment.id, deployment.symbol, othersRunning)
                    }
                },
                activateReplayCapture = activateReplayCapture,
                isReplayOpeningBarQuotesReady = { symbol -> runtime.isOpeningBarQuotesReady(symbol) },
                sessionGateway = sessionGateway,
                executionGateway = executionGateway,
                liquidityBucketRepository = liquidityBucketRepository,
            )
        }
    }
}
