package daytrader.data

import daytrader.domain.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryPositionRepository(
    initial: List<Position> = mockPositions()
) : PositionRepository {
    private val _positions = MutableStateFlow(initial)
    override val positions: StateFlow<List<Position>> = _positions.asStateFlow()
}
