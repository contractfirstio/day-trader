package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.gateway.BrokerGateway
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyDeploymentRepository
import daytrader.data.PositionRepository
import daytrader.presentation.markets.MarketFilterState
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel

data class AppDependencies(
    val marketFilter: MarketFilterState,
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel
)

@Composable
fun rememberAppDependencies(
    positionRepository: PositionRepository,
    brokerGateway: BrokerGateway? = null,
    touchTurnSessionGateway: BrokerGateway? = null,
    ensureLiveMarketData: ((String) -> Unit)? = null,
    releaseLiveMarketData: ((String) -> Unit)? = null
): AppDependencies {
    val strategyRepository = remember { FileStrategyDeploymentRepository() }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    val marketFilter = remember { MarketFilterState() }
    return remember(
        strategyRepository,
        appStateRepository,
        marketFilter,
        positionRepository,
        brokerGateway,
        touchTurnSessionGateway,
        ensureLiveMarketData,
        releaseLiveMarketData
    ) {
        AppDependencies(
            marketFilter = marketFilter,
            strategiesViewModel = StrategiesViewModel(
                repository = strategyRepository,
                appStateRepository = appStateRepository,
                marketFilter = marketFilter,
                brokerGateway = brokerGateway,
                touchTurnSessionGateway = touchTurnSessionGateway,
                ensureLiveMarketData = ensureLiveMarketData,
                releaseLiveMarketData = releaseLiveMarketData
            ),
            positionsViewModel = PositionsViewModel(positionRepository)
        )
    }
}
