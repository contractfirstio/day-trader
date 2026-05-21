package daytrader.data

import daytrader.domain.Position
import kotlinx.coroutines.flow.StateFlow

interface PositionRepository {
    val positions: StateFlow<List<Position>>
}
