package daytrader.data

import daytrader.domain.StrategyDeployment
import kotlinx.coroutines.flow.StateFlow

interface StrategyDeploymentRepository {
    val deployments: StateFlow<List<StrategyDeployment>>
    fun add(deployment: StrategyDeployment)
    fun update(id: String, transform: (StrategyDeployment) -> StrategyDeployment)
    fun remove(id: String)
    fun flushPersistence()
}
