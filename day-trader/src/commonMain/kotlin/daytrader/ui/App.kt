package daytrader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoGraph
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
import daytrader.data.PositionRepository
import daytrader.presentation.navigation.AppScreen
import daytrader.marketdata.MarketQuoteBus
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
    brokerKind: BrokerKind,
    touchTurnSessionGateway: BrokerGateway = brokerGateway,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    quoteBus: MarketQuoteBus? = null,
    getStreamingMarketDataType: (() -> IbStreamingMarketDataType)? = null,
    setStreamingMarketDataType: ((IbStreamingMarketDataType) -> Unit)? = null,
    replayHybridRuntime: ReplayHybridRuntime? = null,
    replayBundle: SessionBundle? = null,
    onRegisterApplicationQuit: ((ApplicationQuitCoordinator) -> Unit)? = null
) {
    val dependencies = rememberAppDependencies(
        positionRepository = positionRepository,
        brokerGateway = brokerGateway,
        touchTurnSessionGateway = touchTurnSessionGateway,
        brokerKind = brokerKind,
        ensureLiveMarketData = ensureLiveMarketData,
        releaseLiveMarketData = releaseLiveMarketData,
        replayHybridRuntime = replayHybridRuntime,
        replayBundle = replayBundle
    )
    var currentScreen by remember { mutableStateOf(AppScreen.STRATEGIES) }
    var showPriceFeedTester by remember { mutableStateOf(false) }
    val selectedMarketZoneId by dependencies.marketFilter.selectedZoneId.collectAsState()
    val strategiesUi by dependencies.strategiesViewModel.uiState.collectAsState()

    val viewModel = dependencies.strategiesViewModel
    DisposableEffect(viewModel) {
        onRegisterApplicationQuit?.invoke(
            ApplicationQuitCoordinator(
                hasRunningSessions = viewModel::hasRunningSessions,
                runningSymbols = viewModel::runningSessionSymbols,
                stopRunningSessions = viewModel::shutdownRunningSessions
            )
        )
        onDispose {
            viewModel.shutdownRunningSessions()
        }
    }

    MaterialTheme {
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
                    onOpenPriceFeedTester = { showPriceFeedTester = true }
                )
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
                val replayController = dependencies.replayController
                val replaySessionBundle = dependencies.replayBundle
                if (replayController != null && replaySessionBundle != null) {
                    ReplayControlBar(
                        bundle = replaySessionBundle,
                        controller = replayController
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
                                    enabled = strategiesUi.globalAutoStartEnabled,
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

                    val connectionState by brokerGateway.connectionState.collectAsState()
                    when (currentScreen) {
                        AppScreen.POSITIONS -> PositionsScreen(
                            viewModel = dependencies.positionsViewModel,
                            connectionState = connectionState
                        )
                        AppScreen.STRATEGIES -> StrategiesScreen(dependencies.strategiesViewModel)
                    }
                }
            }
        }
    }
}
