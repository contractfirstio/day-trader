package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.diagnostics.SessionPriceLog
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.LoggingTouchTurnEngine
import daytrader.gateway.BrokerGateway
import daytrader.domain.InstrumentIdentity
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyDeploymentRepository
import daytrader.data.PositionRepository
import daytrader.presentation.markets.MarketFilterState
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

data class AppDependencies(
    val marketFilter: MarketFilterState,
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel,
    val touchTurnEngine: TouchTurnEnginePort? = null
)

@Composable
fun rememberAppDependencies(
    positionRepository: PositionRepository,
    brokerGateway: BrokerGateway? = null,
    touchTurnSessionGateway: BrokerGateway? = null,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null
): AppDependencies {
    val strategyRepository = remember { FileStrategyDeploymentRepository() }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    val marketFilter = remember { MarketFilterState() }
    val engineScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    SessionPriceLog.install { strategyRepository.deployments.value }
    return remember(
        strategyRepository,
        appStateRepository,
        marketFilter,
        positionRepository,
        brokerGateway,
        touchTurnSessionGateway,
        ensureLiveMarketData,
        releaseLiveMarketData,
        engineScope
    ) {
        val sessionGateway = touchTurnSessionGateway ?: brokerGateway
        val touchTurnEngine: TouchTurnEnginePort? = sessionGateway?.let { session ->
            val engine = TouchTurnEngine(
                sessionGateway = session,
                executionGateway = brokerGateway ?: session,
                repository = strategyRepository,
                scope = engineScope,
                ensureLiveMarketData = ensureLiveMarketData,
                isGlobalAutoStartEnabled = { appStateRepository.state.value.globalAutoStartEnabled },
                releaseLiveMarketData = releaseLiveMarketData
            )
            if (TouchTurnEngineConfig.shadowLogEnabled()) {
                LoggingTouchTurnEngine(engine)
            } else {
                engine
            }
        }
        val viewModel = StrategiesViewModel(
            repository = strategyRepository,
            appStateRepository = appStateRepository,
            marketFilter = marketFilter,
            brokerGateway = brokerGateway,
            touchTurnSessionGateway = touchTurnSessionGateway,
            touchTurnEngine = touchTurnEngine,
            ensureLiveMarketData = ensureLiveMarketData,
            releaseLiveMarketData = releaseLiveMarketData
        )
        touchTurnEngine?.let { engine ->
            if (TouchTurnEngineConfig.useEngine()) {
                engine.start()
            }
        }
        AppDependencies(
            marketFilter = marketFilter,
            strategiesViewModel = viewModel,
            positionsViewModel = PositionsViewModel(positionRepository),
            touchTurnEngine = touchTurnEngine
        )
    }
}
