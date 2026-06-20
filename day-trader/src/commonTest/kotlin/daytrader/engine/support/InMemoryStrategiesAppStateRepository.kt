package daytrader.engine.support

import daytrader.data.StrategiesAppState
import daytrader.data.StrategiesAppStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryStrategiesAppStateRepository(
    initial: StrategiesAppState = StrategiesAppState()
) : StrategiesAppStateRepository {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<StrategiesAppState> = _state.asStateFlow()

    override fun update(transform: (StrategiesAppState) -> StrategiesAppState) {
        _state.update(transform)
    }
}
