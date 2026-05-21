package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyInstanceRepository
import daytrader.data.InMemoryPositionRepository
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel

data class AppDependencies(
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel
)

@Composable
fun rememberAppDependencies(): AppDependencies {
    val strategyRepository = remember { FileStrategyInstanceRepository() }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    val positionRepository = remember { InMemoryPositionRepository() }
    return remember(strategyRepository, appStateRepository, positionRepository) {
        AppDependencies(
            strategiesViewModel = StrategiesViewModel(strategyRepository, appStateRepository),
            positionsViewModel = PositionsViewModel(positionRepository)
        )
    }
}
