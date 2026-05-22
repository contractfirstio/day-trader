package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.data.FileStrategiesAppStateRepository
import daytrader.data.FileStrategyInstanceRepository
import daytrader.data.PositionRepository
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel

data class AppDependencies(
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel
)

@Composable
fun rememberAppDependencies(positionRepository: PositionRepository): AppDependencies {
    val strategyRepository = remember { FileStrategyInstanceRepository() }
    val appStateRepository = remember { FileStrategiesAppStateRepository() }
    return remember(strategyRepository, appStateRepository, positionRepository) {
        AppDependencies(
            strategiesViewModel = StrategiesViewModel(strategyRepository, appStateRepository),
            positionsViewModel = PositionsViewModel(positionRepository)
        )
    }
}
