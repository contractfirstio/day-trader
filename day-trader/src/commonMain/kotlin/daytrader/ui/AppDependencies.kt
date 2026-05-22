package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.broker.IbGatewayConnection
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyInstanceRepository
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
    touchTurnMarketData: IbGatewayConnection? = null
): AppDependencies {
    val strategyRepository = remember { FileStrategyInstanceRepository() }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    val marketFilter = remember { MarketFilterState() }
    return remember(strategyRepository, appStateRepository, marketFilter, positionRepository, touchTurnMarketData) {
        AppDependencies(
            marketFilter = marketFilter,
            strategiesViewModel = StrategiesViewModel(
                repository = strategyRepository,
                appStateRepository = appStateRepository,
                marketFilter = marketFilter,
                touchTurnMarketData = touchTurnMarketData
            ),
            positionsViewModel = PositionsViewModel(positionRepository)
        )
    }
}
