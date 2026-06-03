package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.diagnostics.SessionPriceLog
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.LoggingTouchTurnEngine
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.execution.LoggingExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.domain.InstrumentIdentity
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyDeploymentRepository
import daytrader.data.PositionRepository
import daytrader.data.RunningSessionShutdown
import daytrader.domain.TouchTurnSessionStopTrigger
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
    brokerKind: BrokerKind = BrokerKind.EMULATOR,
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
        brokerKind,
        ensureLiveMarketData,
        releaseLiveMarketData,
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
                sessionGateway = session,
                executionGateway = executionGateway
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
            brokerKind = brokerKind,
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
