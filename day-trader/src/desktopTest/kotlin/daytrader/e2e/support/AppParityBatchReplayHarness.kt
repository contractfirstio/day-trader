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
import daytrader.replay.SessionBundleDirectoryReader
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Desktop batch replay harness that loads captures from disk via [SessionBundleDirectoryReader],
 * matching the production [daytrader.ui.AppDependencies] wiring path.
 */
class AppParityBatchReplayHarness private constructor(
    val repository: StrategyDeploymentRepository,
    val runtime: ReplayHybridRuntime,
    val engine: TouchTurnEnginePort,
    val controller: ReplaySessionController,
    val batchRunner: BatchReplayRunner,
    val replayCaptureCatalog: List<ReplayCaptureRef>,
) {
    fun shutdown() {
        engine.shutdown()
        runtime.shutdown()
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            repository: StrategyDeploymentRepository,
            sessionDirectories: List<Path>,
            globalAutoStartEnabled: () -> Boolean = { false },
        ): AppParityBatchReplayHarness {
            require(sessionDirectories.isNotEmpty()) { "At least one session directory required" }
            val bundles = sessionDirectories.map { dir ->
                SessionBundleDirectoryReader.loadReplayableFromDirectory(dir.toString()).getOrThrow()
            }
            val catalog = sessionDirectories.zip(bundles) { dir, bundle ->
                ReplayCaptureRef(
                    directoryPath = dir.toString(),
                    deploymentId = bundle.deploymentId,
                    symbol = bundle.symbol,
                    sessionDate = bundle.sessionDate,
                    sessionStartedEpochMs = bundle.timeline.sessionStartedEpochMs,
                )
            }
            val primary = bundles.first()
            val clock = ReplayClock(primary.timeline.sessionStartedEpochMs)
            val runtime = ReplayHybridRuntime(primary, clock, scope)
            runtime.start()
            bundles.forEach { runtime.registerBundle(it) }
            val liquidityBucketRepository = InMemoryLiquidityBucketRepository()
            val engine = createAppEngine(
                runtime = runtime,
                clock = clock,
                repository = repository,
                scope = scope,
                catalog = catalog,
                globalAutoStartEnabled = globalAutoStartEnabled,
                liquidityBucketRepository = liquidityBucketRepository,
            )
            runtime.attachSessionEngine(engine)
            runtime.playbackOrchestrator.attach(engine, repository)
            ReplaySessionPlaybackBridge(runtime.playbackOrchestrator, scope).attach(engine)
            engine.start()
            val controller = ReplaySessionController(runtime, repository, engine, scope)
            val batchRunner = BatchReplayRunner(
                controller = controller,
                repository = repository,
                loadBundle = SessionBundleDirectoryReader::loadReplayableFromDirectory,
                restoreEngineGlobalAutoStart = globalAutoStartEnabled,
            )
            return AppParityBatchReplayHarness(
                repository = repository,
                runtime = runtime,
                engine = engine,
                controller = controller,
                batchRunner = batchRunner,
                replayCaptureCatalog = catalog,
            )
        }

        private fun createAppEngine(
            runtime: ReplayHybridRuntime,
            clock: ReplayClock,
            repository: StrategyDeploymentRepository,
            scope: CoroutineScope,
            catalog: List<ReplayCaptureRef>,
            globalAutoStartEnabled: () -> Boolean,
            liquidityBucketRepository: InMemoryLiquidityBucketRepository,
        ): TouchTurnEnginePort {
            val sessionGateway = runtime.marketDataGateway
            val executionGateway = runtime.executionGateway
            val marketData = BrokerGatewayMarketDataProvider(
                gateway = sessionGateway,
                ensureLiveMarketData = { symbol, _ -> runtime.ensureStreamingMarketData(symbol) },
                releaseLiveMarketData = { symbol, _ -> runtime.releaseStreamingMarketData(symbol) },
            )
            val baseExecution = BrokerGatewayExecutionManager(executionGateway)
            val execution: ExecutionManager = if (executionGateway.brokerId != BrokerId.INTERACTIVE_BROKERS) {
                LoggingExecutionManager(baseExecution, executionGateway.brokerId)
            } else {
                baseExecution
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
                activateReplayCapture = { deployment ->
                    val headlessDate = runtime.headlessBacktestSessionDate(deployment.id)
                    if (headlessDate != null) {
                        headlessDate
                    } else {
                        val capture = ReplayBundleResolver.selectCapture(deployment, catalog)
                            ?: return@TouchTurnEngine null
                        val bundle = SessionBundleDirectoryReader
                            .loadReplayableFromDirectory(capture.directoryPath)
                            .getOrNull()
                            ?: return@TouchTurnEngine null
                        val previous = runtime.captureRegistry.bundleFor(deployment.symbol)?.sessionId
                        runtime.registerBundle(bundle)
                        ReplaySessionController.seedDeploymentIfNeeded(repository, bundle)
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
                        bundle.sessionDate
                    }
                },
                isReplayOpeningBarQuotesReady = { symbol -> runtime.isOpeningBarQuotesReady(symbol) },
                sessionGateway = sessionGateway,
                executionGateway = executionGateway,
                liquidityBucketRepository = liquidityBucketRepository,
            )
        }
    }
}
