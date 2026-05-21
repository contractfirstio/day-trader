package daytrader.data

import daytrader.domain.StrategyInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryStrategyInstanceRepository(
    initial: List<StrategyInstance> = mockStrategyInstances()
) : StrategyInstanceRepository {
    private val _instances = MutableStateFlow(initial)
    override val instances: StateFlow<List<StrategyInstance>> = _instances.asStateFlow()

    override fun add(instance: StrategyInstance) {
        _instances.update { it + instance }
    }

    override fun update(id: String, transform: (StrategyInstance) -> StrategyInstance) {
        _instances.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    override fun remove(id: String) {
        _instances.update { it.filterNot { instance -> instance.id == id } }
    }
}
