package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyDeploymentRepository
import daytrader.data.FileWatchlistRepository
import daytrader.data.OpenOrderRepository
import daytrader.data.PositionRepository
import daytrader.data.RunningSessionShutdown
import daytrader.diagnostics.SessionPriceLog
import daytrader.domain.InstrumentIdentity
import daytrader.domain.TouchTurnSessionStopTrigger
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
import daytrader.presentation.orders.OrdersViewModel
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel
import daytrader.presentation.watchlist.WatchlistViewModel
import daytrader.replay.ReplayHybridRuntime
import daytrader.replay.ReplaySessionController
import daytrader.replay.SessionBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

data class AppDependencies(
    val marketFilter: MarketFilterState,
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel,
    val ordersViewModel: OrdersViewModel,
    val watchlistViewModel: WatchlistViewModel,
    val watchlistStrategyCreateBridge: WatchlistStrategyCreateBridge,
    val touchTurnEngine: TouchTurnEnginePort? = null,
    val replayController: ReplaySessionController? = null,
    val replayBundle: SessionBundle? = null
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
    replayBundle: SessionBundle? = null
): AppDependencies {
    val strategyRepository = remember { FileStrategyDeploymentRepository() }
    val watchlistRepository = remember(brokerKind) { FileWatchlistRepository(brokerKind = brokerKind) }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    val marketFilter = remember { MarketFilterState() }
    val engineScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    SessionPriceLog.install { strategyRepository.deployments.value }
    return remember(
        strategyRepository,
        watchlistRepository,
        appStateRepository,
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
        engineScope
    ) {
        val sessionGateway = touchTurnSessionGateway ?: brokerGateway
        (brokerGateway ?: sessionGateway)?.let { executionGateway ->
            RunningSessionShutdown.stopAllRunning(
                repository = strategyRepository,
                gateway = executionGateway,
                brokerKind = brokerKind,
                trigger = TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN
            )
        }
        val replayClock = replayHybridRuntime?.clock
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
            val baseExecution = BrokerGatewayExecutionManager(executionGateway)
            val execution = if (executionGateway.brokerId == BrokerId.INTERACTIVE_BROKERS) {
                baseExecution
            } else {
                LoggingExecutionManager(baseExecution, executionGateway.brokerId)
            }
            val engine = TouchTurnEngine(
                marketData = marketData,
                execution = execution,
                repository = strategyRepository,
                scope = engineScope,
                brokerKind = brokerKind,
                isGlobalAutoStartEnabled = { appStateRepository.state.value.globalAutoStartEnabled },
                nowEpochMillis = replayClock?.let { clock -> { clock.now() } }
                    ?: { System.currentTimeMillis() },
                delayMillis = replayClock?.let { clock -> clock::delayMillis }
                    ?: { ms -> delay(ms) },
                sessionGateway = session,
                executionGateway = executionGateway
            )
            if (TouchTurnEngineConfig.shadowLogEnabled()) {
                LoggingTouchTurnEngine(engine)
            } else {
                engine
            }
        }
        val watchlistStrategyCreateBridge = WatchlistStrategyCreateBridge()
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
            watchlistRepository = watchlistRepository
        )
        val watchlistViewModel = WatchlistViewModel(
            repository = watchlistRepository,
            strategyDeploymentRepository = strategyRepository,
            brokerGateway = brokerGateway,
            touchTurnSessionGateway = touchTurnSessionGateway,
            brokerKind = brokerKind,
            ensureLiveMarketData = ensureLiveMarketData,
            onRequestStrategyDeploymentCreate = watchlistStrategyCreateBridge::requestCreate,
            onDeleteLinkedDeployment = viewModel::deleteDeploymentById
        )
        watchlistStrategyCreateBridge.linkDeploymentToWatchlistEntry =
            watchlistViewModel::linkStrategyDeploymentToEntry
        touchTurnEngine?.let { engine ->
            if (TouchTurnEngineConfig.useEngine()) {
                engine.start()
            }
        }
        val replayController = if (replayHybridRuntime != null && replayBundle != null && touchTurnEngine != null) {
            ReplaySessionController(
                bundle = replayBundle,
                runtime = replayHybridRuntime,
                repository = strategyRepository,
                engine = touchTurnEngine,
                scope = engineScope
            ).also { it.seedDeploymentIfNeeded() }
        } else {
            null
        }
        AppDependencies(
            marketFilter = marketFilter,
            strategiesViewModel = viewModel,
            positionsViewModel = PositionsViewModel(positionRepository),
            ordersViewModel = OrdersViewModel(
                repository = openOrderRepository,
                watchlistRepository = watchlistRepository,
                brokerKind = brokerKind
            ),
            watchlistViewModel = watchlistViewModel,
            watchlistStrategyCreateBridge = watchlistStrategyCreateBridge,
            touchTurnEngine = touchTurnEngine,
            replayController = replayController,
            replayBundle = replayBundle
        )
    }
}
