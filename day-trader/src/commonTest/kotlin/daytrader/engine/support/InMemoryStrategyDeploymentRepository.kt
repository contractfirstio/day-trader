package daytrader.engine.support

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.StrategyDeployment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryStrategyDeploymentRepository(
    initial: List<StrategyDeployment> = emptyList()
) : StrategyDeploymentRepository {
    private val _deployments = MutableStateFlow(initial)
    override val deployments: StateFlow<List<StrategyDeployment>> = _deployments.asStateFlow()

    override fun add(deployment: StrategyDeployment) {
        _deployments.update { it + deployment }
    }

    override fun update(id: String, transform: (StrategyDeployment) -> StrategyDeployment) {
        _deployments.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    override fun remove(id: String) {
        _deployments.update { it.filterNot { deployment -> deployment.id == id } }
    }

    override fun flushPersistence() = Unit
}
