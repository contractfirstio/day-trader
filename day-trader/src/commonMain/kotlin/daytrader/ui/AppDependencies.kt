package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import daytrader.data.InMemoryPositionRepository
import daytrader.data.InMemoryStrategyInstanceRepository
import daytrader.presentation.positions.PositionsViewModel
import daytrader.presentation.strategies.StrategiesViewModel

data class AppDependencies(
    val strategiesViewModel: StrategiesViewModel,
    val positionsViewModel: PositionsViewModel
)

@Composable
fun rememberAppDependencies(): AppDependencies {
    val strategyRepository = remember { InMemoryStrategyInstanceRepository() }
    val positionRepository = remember { InMemoryPositionRepository() }
    return remember(strategyRepository, positionRepository) {
        AppDependencies(
            strategiesViewModel = StrategiesViewModel(strategyRepository),
            positionsViewModel = PositionsViewModel(positionRepository)
        )
    }
}
