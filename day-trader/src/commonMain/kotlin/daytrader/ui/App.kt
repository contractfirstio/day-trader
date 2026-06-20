package daytrader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import daytrader.domain.InstrumentIdentity
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.IbStreamingMarketDataType
import daytrader.data.OpenOrderRepository
import daytrader.data.PositionRepository
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiFaultBus
import daytrader.presentation.ui.UiRecoveryBus
import daytrader.marketdata.MarketQuoteBus
import daytrader.platform.TradingClock
import daytrader.platform.WallClock
import daytrader.platform.AppFileSystem
import daytrader.diagnostics.AppHealthCollector
import daytrader.diagnostics.AppHealthSnapshot
import daytrader.diagnostics.DebugBundleExporter
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.data.PortfolioExposureCalculator
import daytrader.data.PortfolioExposureLimits
import daytrader.replay.ReplayHybridRuntime
import daytrader.replay.SessionBundle
import daytrader.ui.tools.PriceFeedTesterDialog
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark

@Composable
fun App(
    brokerGateway: BrokerGateway,
    positionRepository: PositionRepository,
    openOrderRepository: OpenOrderRepository,
    brokerKind: BrokerKind,
    touchTurnSessionGateway: BrokerGateway = brokerGateway,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    quoteBus: MarketQuoteBus? = null,
    getStreamingMarketDataType: (() -> IbStreamingMarketDataType)? = null,
    setStreamingMarketDataType: ((IbStreamingMarketDataType) -> Unit)? = null,
    replayHybridRuntime: ReplayHybridRuntime? = null,
    replayBundle: SessionBundle? = null,
    replayCaptureCatalog: List<daytrader.replay.ReplayCaptureRef> = emptyList(),
    replaySeedDirectoryPaths: List<String> = emptyList(),
    loadReplayBundle: (String) -> Result<SessionBundle> = {
        Result.failure(IllegalStateException("Replay bundle loader not configured"))
    },
    tradingClock: TradingClock = WallClock,
    onRegisterApplicationQuit: ((ApplicationQuitCoordinator) -> Unit)? = null,
    onChangeBrokerMode: (() -> Unit)? = null
) {
    val dependencies = rememberAppDependencies(
        positionRepository = positionRepository,
        openOrderRepository = openOrderRepository,
        brokerGateway = brokerGateway,
        touchTurnSessionGateway = touchTurnSessionGateway,
        brokerKind = brokerKind,
        ensureLiveMarketData = ensureLiveMarketData,
        releaseLiveMarketData = releaseLiveMarketData,
        replayHybridRuntime = replayHybridRuntime,
        replayBundle = replayBundle,
        replayCaptureCatalog = replayCaptureCatalog,
        replaySeedDirectoryPaths = replaySeedDirectoryPaths,
        loadReplayBundle = loadReplayBundle,
        tradingClock = tradingClock
    )
    var currentScreen by remember { mutableStateOf(AppScreen.STRATEGIES) }
    var screenRetryNonce by remember { mutableStateOf(0) }
    val globalUiRecoveryGeneration by UiRecoveryBus.generation.collectAsState()
    var showPriceFeedTester by remember { mutableStateOf(false) }
    var debugHealthSnapshot by remember { mutableStateOf<AppHealthSnapshot?>(null) }
    var debugExportPath by remember { mutableStateOf<String?>(null) }
    var showKillSwitchDialog by remember { mutableStateOf(false) }
    val selectedMarketZoneId by dependencies.marketFilter.selectedZoneId.collectAsState()
    val deployments by dependencies.strategyRepository.deployments.collectAsState()
    val brokerOpenOrders by brokerGateway.openOrders.collectAsState()
    val brokerPositions by brokerGateway.positions.collectAsState()
    val portfolioExposure = remember(deployments) { PortfolioExposureCalculator.calculate(deployments) }
    val portfolioExposureOverCap = remember(portfolioExposure) { PortfolioExposureLimits.isOverCap(portfolioExposure) }
    val portfolioExposureLabel = remember(portfolioExposure, portfolioExposureOverCap) {
        when {
            portfolioExposure.runningDeploymentCount == 0 -> null
            portfolioExposureOverCap -> {
                val cap = PortfolioExposureLimits.configuredMaxAtRisk()
                "$${portfolioExposure.totalMaxAtRiskUsd} at risk · ${portfolioExposure.runningDeploymentCount} running" +
                    (cap?.let { " (cap $$it)" } ?: "")
            }
            else -> {
                "$${portfolioExposure.totalMaxAtRiskUsd} at risk · ${portfolioExposure.runningDeploymentCount} running"
            }
        }
    }
    val runningSymbols = remember(deployments) {
        deployments.filter { it.status == daytrader.domain.DeploymentStatus.RUNNING }.map { it.symbol }
    }
    val killSwitchEnabled = remember(portfolioExposure, brokerOpenOrders, brokerPositions) {
        portfolioExposure.runningDeploymentCount > 0 ||
            brokerOpenOrders.isNotEmpty() ||
            brokerPositions.any { it.quantity != 0 }
    }
    val orphanBrokerActivity = remember(runningSymbols, brokerOpenOrders, brokerPositions) {
        brokerOpenOrders.any { order ->
            runningSymbols.none { daytrader.broker.SymbolMarkets.symbolsMatch(order.symbol, it) }
        } || brokerPositions.any { position ->
            position.quantity != 0 &&
                runningSymbols.none { daytrader.broker.SymbolMarkets.symbolsMatch(position.symbol, it) }
        }
    }
    val strategiesListState by dependencies.strategiesViewModel.listState.collectAsState()
    val watchlistUi by dependencies.watchlistViewModel.uiState.collectAsState()

    SideEffect {
        dependencies.watchlistStrategyCreateBridge.navigateToStrategies = {
            currentScreen = AppScreen.STRATEGIES
        }
        dependencies.watchlistStrategyCreateBridge.showStrategyAddDialog = { prefill ->
            dependencies.strategiesViewModel.onShowAddDialog(prefill)
        }
    }

    val viewModel = dependencies.strategiesViewModel
    DisposableEffect(viewModel) {
        onRegisterApplicationQuit?.invoke(
            ApplicationQuitCoordinator(
                hasRunningSessions = viewModel::hasRunningSessions,
                runningSymbols = viewModel::runningSessionSymbols,
                stopRunningSessions = viewModel::shutdownRunningSessions,
                hasActiveMarketDataCaptures = viewModel::hasActiveMarketDataCaptures,
                stopMarketDataCaptures = { viewModel.stopAllSessionMarketDataCaptures() }
            )
        )
        onDispose {
            viewModel.shutdownRunningSessions()
        }
    }

    MaterialTheme {
        UiSecondTickProvider {
            Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    brokerGateway = brokerGateway,
                    brokerKind = brokerKind,
                    marketDataGateway = if (touchTurnSessionGateway !== brokerGateway) {
                        touchTurnSessionGateway
                    } else {
                        null
                    },
                    selectedMarketZoneId = selectedMarketZoneId,
                    onMarketClick = dependencies.marketFilter::toggle,
                    onOpenPriceFeedTester = { showPriceFeedTester = true },
                    onChangeBrokerMode = onChangeBrokerMode,
                    portfolioExposureLabel = portfolioExposureLabel,
                    portfolioExposureOverCap = portfolioExposureOverCap,
                    onKillSwitch = { showKillSwitchDialog = true },
                    killSwitchEnabled = killSwitchEnabled,
                    onExportDebugInfo = {
                        val snapshot = AppHealthCollector.collect(
                            brokerKind = brokerKind,
                            dataDirectory = AppFileSystem.appDataDirectory(),
                            executionGateway = brokerGateway,
                            marketDataGateway = touchTurnSessionGateway.takeIf { it !== brokerGateway },
                            deployments = deployments,
                            trackedDataFiles = DebugBundleExporter.trackedPersistenceFiles(),
                        )
                        debugExportPath = DebugBundleExporter.export(snapshot)
                        debugHealthSnapshot = snapshot
                        TimestampedConsoleLog.line(
                            "DEBUG_BUNDLE",
                            "exported health snapshot to ${debugExportPath.orEmpty()}",
                        )
                    },
                )
                debugHealthSnapshot?.let { snapshot ->
                    DebugHealthDialog(
                        snapshot = snapshot,
                        exportPath = debugExportPath,
                        onDismiss = {
                            debugHealthSnapshot = null
                            debugExportPath = null
                        },
                    )
                }
                if (showKillSwitchDialog) {
                    GlobalKillSwitchDialog(
                        exposure = portfolioExposure,
                        runningSymbols = runningSymbols,
                        orphanBrokerActivity = orphanBrokerActivity,
                        onConfirm = {
                            showKillSwitchDialog = false
                            viewModel.activateGlobalKillSwitch()
                        },
                        onDismiss = { showKillSwitchDialog = false },
                    )
                }
                if (showPriceFeedTester) {
                    PriceFeedTesterDialog(
                        brokerKind = brokerKind,
                        brokerGateway = brokerGateway,
                        marketDataGateway = if (touchTurnSessionGateway !== brokerGateway) {
                            touchTurnSessionGateway
                        } else {
                            null
                        },
                        quoteBus = quoteBus,
                        ensureLiveMarketData = ensureLiveMarketData,
                        releaseLiveMarketData = releaseLiveMarketData,
                        getStreamingMarketDataType = getStreamingMarketDataType,
                        setStreamingMarketDataType = setStreamingMarketDataType,
                        onDismiss = { showPriceFeedTester = false }
                    )
                }
                watchlistUi.pendingDiaryNotification?.let { notification ->
                    WatchlistPlanDiaryNotificationDialog(
                        notification = notification,
                        onView = {
                            currentScreen = AppScreen.WATCHLIST
                            dependencies.watchlistViewModel.onViewDiaryNotification()
                        },
                        onDismissReminder = dependencies.watchlistViewModel::onDismissDiaryNotification
                    )
                }
                val replayController = dependencies.replayController
                val replaySessionBundle = replayHybridRuntime?.bundle ?: dependencies.replayBundle
                val replaySettingsRepository = dependencies.replaySettingsRepository
                if (replayController != null && replaySessionBundle != null && replaySettingsRepository != null) {
                    ReplayControlBar(
                        bundle = replaySessionBundle,
                        controller = replayController,
                        batchReplayRunner = dependencies.batchReplayRunner,
                        replayCaptureCatalog = dependencies.replayCaptureCatalog,
                        replaySeedDirectoryPaths = dependencies.replaySeedDirectoryPaths,
                        loadReplayBundle = dependencies.loadReplayBundle,
                        replaySettingsRepository = replaySettingsRepository
                    )
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = SurfaceDark,
                        header = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = "Logo",
                                    tint = BrandRed,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                GlobalAutoStartKillSwitchRail(
                                    enabled = strategiesListState.globalAutoStartEnabled,
                                    onEnabledChange = dependencies.strategiesViewModel::onGlobalAutoStartEnabledChange
                                )
                            }
                        }
                    ) {
                        NavigationRailItem(
                            selected = currentScreen == AppScreen.STRATEGIES,
                            onClick = { currentScreen = AppScreen.STRATEGIES },
                            icon = { Icon(Icons.Default.AutoGraph, "Strategies") },
                            label = { Text("Strategies") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = GainGreen,
                                selectedTextColor = Color.White,
                                indicatorColor = Color.Transparent
                            )
                        )
                        NavigationRailItem(
                            selected = currentScreen == AppScreen.WATCHLIST,
                            onClick = { currentScreen = AppScreen.WATCHLIST },
                            icon = { Icon(Icons.Default.Star, "Watchlist") },
                            label = { Text("Watchlist") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = GainGreen,
                                selectedTextColor = Color.White,
                                indicatorColor = Color.Transparent
                            )
                        )
                        NavigationRailItem(
                            selected = currentScreen == AppScreen.LIQUIDITY,
                            onClick = { currentScreen = AppScreen.LIQUIDITY },
                            icon = { Icon(Icons.Default.AccountBalance, "Liquidity") },
                            label = { Text("Liquidity") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = GainGreen,
                                selectedTextColor = Color.White,
                                indicatorColor = Color.Transparent
                            )
                        )
                        NavigationRailItem(
                            selected = currentScreen == AppScreen.ORDERS,
                            onClick = { currentScreen = AppScreen.ORDERS },
                            icon = { Icon(Icons.Default.List, "Orders") },
                            label = { Text("Orders") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = GainGreen,
                                selectedTextColor = Color.White,
                                indicatorColor = Color.Transparent
                            )
                        )
                        NavigationRailItem(
                            selected = currentScreen == AppScreen.POSITIONS,
                            onClick = { currentScreen = AppScreen.POSITIONS },
                            icon = { Icon(Icons.Default.Wallet, "Positions") },
                            label = { Text("Positions") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = GainGreen,
                                selectedTextColor = Color.White,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }

                    SafeScreenHost(
                        screen = currentScreen,
                        retryNonce = screenRetryNonce + globalUiRecoveryGeneration,
                        onRetry = {
                            UiFaultBus.clear(currentScreen)
                            screenRetryNonce++
                        },
                        onGoToStrategies = {
                            UiFaultBus.clear(currentScreen)
                            currentScreen = AppScreen.STRATEGIES
                            screenRetryNonce++
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        val connectionState by brokerGateway.connectionState.collectAsState()
                        when (currentScreen) {
                            AppScreen.POSITIONS -> PositionsScreen(
                                viewModel = dependencies.positionsViewModel,
                                connectionState = connectionState
                            )
                            AppScreen.LIQUIDITY -> LiquidityAllocatorScreen(
                                viewModel = dependencies.liquidityAllocatorViewModel
                            )
                            AppScreen.ORDERS -> OrdersScreen(
                                viewModel = dependencies.ordersViewModel,
                                connectionState = connectionState,
                                brokerKind = brokerKind
                            )
                            AppScreen.STRATEGIES -> StrategiesScreen(dependencies.strategiesViewModel)
                            AppScreen.WATCHLIST -> WatchlistScreen(dependencies.watchlistViewModel)
                        }
                    }
                }
            }
        }
        }
    }
}
