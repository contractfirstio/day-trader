package daytrader.data

import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class StrategyDeploymentRepositoryRemoveTest {
    @Test
    fun remove_deletesOnlyMatchingDeployment() {
        val keepA = defaultStrategyDeployment(StrategyType.TOUCH_AND_TURN_SCALPER, "AAPL", 10_000)
        val remove = defaultStrategyDeployment(StrategyType.TOUCH_AND_TURN_SCALPER, "TSLA", 10_000)
        val keepB = defaultStrategyDeployment(StrategyType.TOUCH_AND_TURN_SCALPER, "MSFT", 10_000)
        val repo = InMemoryStrategyDeploymentRepository(listOf(keepA, remove, keepB))

        repo.remove(remove.id)

        assertEquals(listOf(keepA.id, keepB.id), repo.deployments.value.map { it.id })
    }
}
