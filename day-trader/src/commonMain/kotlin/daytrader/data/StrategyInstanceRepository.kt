package daytrader.data

import daytrader.domain.StrategyInstance
import kotlinx.coroutines.flow.StateFlow

interface StrategyInstanceRepository {
    val instances: StateFlow<List<StrategyInstance>>
    fun add(instance: StrategyInstance)
    fun update(id: String, transform: (StrategyInstance) -> StrategyInstance)
    fun remove(id: String)
}
