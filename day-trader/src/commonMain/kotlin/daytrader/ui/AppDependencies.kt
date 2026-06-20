package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import daytrader.data.FileLiquidityBucketRepository
import daytrader.data.FileReplaySettingsRepository
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyDeploymentRepository
import daytrader.data.PersistenceDrain
import daytrader.data.StrategyDeploymentRepository
import daytrader.data.FileWatchlistRepository
import daytrader.data.ReplaySettingsRepository
import daytrader.data.LiquidityBucketRepository
import daytrader.data.StrategiesAppStateRepository
import daytrader.data.WatchlistRepository
import daytrader.data.OpenOrderRepository
import daytrader.data.PositionRepository
import daytrader.data.ReversalScoreService
import daytrader.data.RunningSessionShutdown
import daytrader.diagnostics.SessionPriceLog
import daytrader.domain.InstrumentIdentity
import daytrader.broker.SymbolMarkets
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.engine.LoggingTouchTurnEngine
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.execution.LoggingExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.presentation.markets.MarketFilterState
import daytrader.presentation.liquidity.LiquidityAllocatorViewModel
import daytrader.presentation.orders.OrdersViewModel
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel
import daytrader.execution.ExecutionManager
import daytrader.presentation.watchlist.WatchlistViewModel
import daytrader.diagnostics.SessionTrace
import daytrader.replay.BatchReplayRunner
import daytrader.replay.ReplayBundleResolver
import daytrader.replay.ReplayCaptureRef
import daytrader.replay.ReplayHybridRuntime
import daytrader.replay.ReplaySessionController
import daytrader.replay.ReplaySessionPlaybackBridge
import daytrader.replay.ReplaySessionTiming
import daytrader.replay.SessionBundle
import daytrader.platform.MutableTradingClock
import daytrader.platform.TradingClock
import daytrader.platform.WallClock
import daytrader.platform.defaultMacroYieldDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppDependencies(
    val marketFilter: MarketFilterState,
    val strategyRepository: StrategyDeploymentRepository,
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel,
    val ordersViewModel: OrdersViewModel,
    val watchlistViewModel: WatchlistViewModel,
    val liquidityAllocatorViewModel: LiquidityAllocatorViewModel,
    val watchlistStrategyCreateBridge: WatchlistStrategyCreateBridge,
    val touchTurnEngine: TouchTurnEnginePort? = null,
    val replayController: ReplaySessionController? = null,
    val batchReplayRunner: BatchReplayRunner? = null,
    val replayBundle: SessionBundle? = null,
    val replayCaptureCatalog: List<ReplayCaptureRef> = emptyList(),
    val replaySeedDirectoryPaths: List<String> = emptyList(),
    val loadReplayBundle: (String) -> Result<SessionBundle> = {
        Result.failure(IllegalStateException("Replay bundle loader not configured"))
    },
    val replaySettingsRepository: ReplaySettingsRepository? = null,
    val drainPersistenceBlocking: () -> Unit = {},
)

@Composable
fun rememberAppDependencies(
    positionRepository: PositionRepository,
    openOrderRepository: OpenOrderRepository,
    brokerGateway: BrokerGateway? = null,
    touchTurnSessionGateway: BrokerGateway? = null,
    brokerKind: BrokerKind = BrokerKind.EMULATOR,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    replayHybridRuntime: ReplayHybridRuntime? = null,
    replayBundle: SessionBundle? = null,
    replayCaptureCatalog: List<ReplayCaptureRef> = emptyList(),
    replaySeedDirectoryPaths: List<String> = emptyList(),
    loadReplayBundle: (String) -> Result<SessionBundle> = {
        Result.failure(IllegalStateException("Replay bundle loader not configured"))
    },
    tradingClock: TradingClock = WallClock
): AppDependencies {
    val strategyRepository = remember { FileStrategyDeploymentRepository() }
    val liquidityBucketRepository = remember { FileLiquidityBucketRepository() }
    val watchlistRepository = remember(brokerKind) { FileWatchlistRepository(brokerKind = brokerKind) }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    val marketFilter = remember { MarketFilterState() }
    val engineJob = remember { SupervisorJob() }
    val engineScope = remember(engineJob) { CoroutineScope(engineJob + Dispatchers.Default) }
    val replaySettingsRepository = remember(brokerKind) {
        if (brokerKind == BrokerKind.REPLAY) FileReplaySettingsRepository() else null
    }
    SessionPriceLog.install { strategyRepository.deployments.value }
    val dependencies = remember(
        strategyRepository,
        liquidityBucketRepository,
        watchlistRepository,
        appStateRepository,
        replaySettingsRepository,
        marketFilter,
        positionRepository,
        openOrderRepository,
        brokerGateway,
        touchTurnSessionGateway,
        brokerKind,
        ensureLiveMarketData,
        releaseLiveMarketData,
        replayHybridRuntime,
        replayBundle,
        replayCaptureCatalog,
        replaySeedDirectoryPaths,
        loadReplayBundle,
        tradingClock,
        engineScope
    ) {
        val sessionGateway = touchTurnSessionGateway ?: brokerGateway
        val mutableClock = tradingClock as? MutableTradingClock
        val activateReplayCapture: ((StrategyDeployment) -> String?)? =
            if (brokerKind == BrokerKind.REPLAY && replayHybridRuntime != null) {
                { deployment ->
                    val capture = ReplayBundleResolver.selectCapture(deployment, replayCaptureCatalog)
                    val bundle = capture?.let { loadReplayBundle(it.directoryPath).getOrNull() }
                    if (capture == null || bundle == null) {
                        null
                    } else {
                        val previous = replayHybridRuntime.captureRegistry.bundleFor(deployment.symbol)?.sessionId
                        replayHybridRuntime.registerBundle(bundle)
                        ReplaySessionController.seedDeploymentIfNeeded(strategyRepository, bundle)
                        SessionTrace.log(
                            type = "replay_capture_activated",
                            deploymentId = deployment.id,
                            symbol = deployment.symbol,
                            details = mapOf(
                                "captureDirectory" to capture.directoryPath,
                                "captureSessionId" to bundle.sessionId,
                                "previousSessionId" to (previous ?: "null"),
                                "captureSessionDate" to (bundle.sessionDate ?: "null")
                            )
                        )
                        bundle.sessionDate
                    }
                }
            } else {
                null
            }
        val onReplaySessionStarting: ((StrategyDeployment, String) -> Unit)? =
            if (brokerKind == BrokerKind.REPLAY && replayHybridRuntime != null && mutableClock != null) {
                { instance, sessionDate ->
                    val othersRunning = strategyRepository.deployments.value.any {
                        it.status == DeploymentStatus.RUNNING && it.id != instance.id
                    }
                    if (!othersRunning) {
                        ReplaySessionTiming.alignClockToSessionOpen(mutableClock, instance, sessionDate)
                    }
                    replayHybridRuntime.prepareForSession(
                        instanceId = instance.id,
                        symbol = instance.symbol,
                        otherSessionsRunning = othersRunning
                    )
                }
            } else {
                null
            }
        val executionManager: ExecutionManager? = (brokerGateway ?: sessionGateway)?.let { executionGateway ->
            val baseExecution = BrokerGatewayExecutionManager(executionGateway)
            if (executionGateway.brokerId == BrokerId.INTERACTIVE_BROKERS) {
                baseExecution
            } else {
                LoggingExecutionManager(baseExecution, executionGateway.brokerId)
            }
        }
        val touchTurnEngine: TouchTurnEnginePort? = sessionGateway?.let { session ->
            val executionGateway = brokerGateway ?: session
            val marketDataGateway = if (
                touchTurnSessionGateway != null &&
                brokerGateway != null &&
                touchTurnSessionGateway !== brokerGateway
            ) {
                touchTurnSessionGateway
            } else {
                executionGateway
            }
            val marketData = BrokerGatewayMarketDataProvider(
                gateway = marketDataGateway,
                ensureLiveMarketData = ensureLiveMarketData,
                releaseLiveMarketData = releaseLiveMarketData
            )
            val execution = executionManager ?: BrokerGatewayExecutionManager(executionGateway)
            val engine = TouchTurnEngine(
                marketData = marketData,
                execution = execution,
                repository = strategyRepository,
                scope = engineScope,
                brokerKind = brokerKind,
                isGlobalAutoStartEnabled = { appStateRepository.state.value.globalAutoStartEnabled },
                nowEpochMillis = tradingClock::nowEpochMillis,
                delayMillis = tradingClock::delayMillis,
                onReplaySessionStarting = onReplaySessionStarting,
                activateReplayCapture = activateReplayCapture,
                isReplayOpeningBarQuotesReady = replayHybridRuntime?.let { runtime ->
                    { symbol -> runtime.isOpeningBarQuotesReady(symbol) }
                },
                sessionGateway = session,
                executionGateway = executionGateway,
                liquidityBucketRepository = liquidityBucketRepository
            )
            if (TouchTurnEngineConfig.shadowLogEnabled()) {
                LoggingTouchTurnEngine(engine)
            } else {
                engine
            }
        }
        val watchlistStrategyCreateBridge = WatchlistStrategyCreateBridge()
        fun replayTurboActive(): Boolean {
            if (brokerKind != BrokerKind.REPLAY) return false
            if (replaySettingsRepository?.settings?.value?.turboDuringPlayback != true) return false
            return replayHybridRuntime?.playbackOrchestrator?.isPlaying() == true
        }
        val viewModel = StrategiesViewModel(
            repository = strategyRepository,
            appStateRepository = appStateRepository,
            marketFilter = marketFilter,
            brokerGateway = brokerGateway,
            touchTurnSessionGateway = touchTurnSessionGateway,
            brokerKind = brokerKind,
            touchTurnEngine = touchTurnEngine,
            ensureLiveMarketData = ensureLiveMarketData,
            releaseLiveMarketData = releaseLiveMarketData,
            onDeploymentCreated = watchlistStrategyCreateBridge::onDeploymentCreated,
            watchlistRepository = watchlistRepository,
            tradingClock = tradingClock,
            replayTurboActive = ::replayTurboActive,
        )
        val liquidityAllocatorViewModel = LiquidityAllocatorViewModel(
            deploymentRepository = strategyRepository,
            openOrderRepository = openOrderRepository,
            liquidityBucketRepository = liquidityBucketRepository,
            brokerGateway = brokerGateway ?: touchTurnSessionGateway,
            executionManager = executionManager,
            skipQuoteUiRefresh = ::replayTurboActive,
        )
        val watchlistViewModel = WatchlistViewModel(
            repository = watchlistRepository,
            strategyDeploymentRepository = strategyRepository,
            brokerGateway = brokerGateway,
            touchTurnSessionGateway = touchTurnSessionGateway,
            brokerKind = brokerKind,
            ensureLiveMarketData = ensureLiveMarketData,
            onRequestStrategyDeploymentCreate = watchlistStrategyCreateBridge::requestCreate,
            onDeleteLinkedDeployment = viewModel::deleteDeploymentById,
            reversalScoreService = ReversalScoreService(defaultMacroYieldDataProvider())
        )
        watchlistStrategyCreateBridge.linkDeploymentToWatchlistEntry =
            watchlistViewModel::linkStrategyDeploymentToEntry
        val replayController = if (replayHybridRuntime != null && touchTurnEngine != null) {
            ReplaySessionController(
                runtime = replayHybridRuntime,
                repository = strategyRepository,
                engine = touchTurnEngine,
                scope = engineScope
            )
        } else {
            null
        }
        val batchReplayRunner = replayController?.let { controller ->
            BatchReplayRunner(
                controller = controller,
                repository = strategyRepository,
                loadBundle = loadReplayBundle
            )
        }
        AppDependencies(
            marketFilter = marketFilter,
            strategyRepository = strategyRepository,
            strategiesViewModel = viewModel,
            positionsViewModel = PositionsViewModel(positionRepository),
            ordersViewModel = OrdersViewModel(
                repository = openOrderRepository,
                watchlistRepository = watchlistRepository,
                brokerKind = brokerKind
            ),
            watchlistViewModel = watchlistViewModel,
            liquidityAllocatorViewModel = liquidityAllocatorViewModel,
            watchlistStrategyCreateBridge = watchlistStrategyCreateBridge,
            touchTurnEngine = touchTurnEngine,
            replayController = replayController,
            batchReplayRunner = batchReplayRunner,
            replayBundle = replayHybridRuntime?.bundle ?: replayBundle,
            replayCaptureCatalog = replayCaptureCatalog,
            replaySeedDirectoryPaths = replaySeedDirectoryPaths,
            loadReplayBundle = loadReplayBundle,
            replaySettingsRepository = replaySettingsRepository,
            drainPersistenceBlocking = {
                PersistenceDrain.flushAllBlocking(
                    deployments = strategyRepository,
                    watchlists = watchlistRepository,
                    liquidity = liquidityBucketRepository,
                    appState = appStateRepository,
                    replaySettings = replaySettingsRepository,
                )
            },
        )
    }
    LaunchedEffect(
        dependencies,
        strategyRepository,
        brokerKind,
        replaySeedDirectoryPaths,
        brokerGateway,
        touchTurnSessionGateway,
        replayHybridRuntime,
        replaySettingsRepository,
    ) {
        strategyRepository.awaitHydrated()
        withContext(Dispatchers.Default) {
            if (brokerKind == BrokerKind.REPLAY && replaySeedDirectoryPaths.isNotEmpty()) {
                ReplaySessionController.seedDeploymentsFromDirectories(
                    repository = strategyRepository,
                    directoryPaths = replaySeedDirectoryPaths,
                    loadBundle = loadReplayBundle
                )
                replaySeedDirectoryPaths.distinct().forEach { path ->
                    loadReplayBundle(path).onSuccess { bundle ->
                        replayHybridRuntime?.registerBundle(bundle)
                    }
                }
                strategyRepository.flushPersistenceBlocking()
            }
            val sessionGateway = touchTurnSessionGateway ?: brokerGateway
            (brokerGateway ?: sessionGateway)?.let { executionGateway ->
                RunningSessionShutdown.stopAllRunning(
                    repository = strategyRepository,
                    gateway = executionGateway,
                    brokerKind = brokerKind,
                    trigger = TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN
                )
            }
        }
        val touchTurnEngine = dependencies.touchTurnEngine
        touchTurnEngine?.let { engine ->
            if (TouchTurnEngineConfig.useEngine()) {
                engine.start()
            }
        }
        if (replayHybridRuntime != null && touchTurnEngine != null) {
            replaySettingsRepository?.let { settingsRepository ->
                replayHybridRuntime.playbackOrchestrator.quoteIntervalMs = {
                    settingsRepository.settings.value.quoteIntervalMs
                }
            }
            replayHybridRuntime.attachSessionEngine(touchTurnEngine)
            replayHybridRuntime.playbackOrchestrator.attach(touchTurnEngine, strategyRepository)
            ReplaySessionPlaybackBridge(
                orchestrator = replayHybridRuntime.playbackOrchestrator,
                scope = engineScope
            ).attach(touchTurnEngine)
            dependencies.replayController?.seedDeploymentIfNeeded()
        }
    }
    DisposableEffect(dependencies, engineJob) {
        onDispose {
            dependencies.touchTurnEngine?.shutdown()
            replayHybridRuntime?.playbackOrchestrator?.stopAll()
            dependencies.drainPersistenceBlocking()
            engineJob.cancel()
        }
    }
    return dependencies
}
