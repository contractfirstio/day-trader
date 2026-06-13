package daytrader.execution

import daytrader.domain.TouchTurnOrderPlan
import daytrader.gateway.BrokerId

/**
 * Decorator that delegates to a live manager (logging hooks removed with volume-exhaustion rules).
 */
class LoggingExecutionManager(
    private val delegate: ExecutionManager,
    private val brokerId: BrokerId
) : ExecutionManager by delegate {

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan): BracketPlacementResult =
        delegate.placeTouchTurnBracket(plan)

    override suspend fun cancelOrder(orderId: Int): Boolean = delegate.cancelOrder(orderId)
}
