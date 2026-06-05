package daytrader.execution

import daytrader.domain.TouchTurnOrderPlan
import daytrader.engine.touchturn.VolumeExhaustionLog
import daytrader.gateway.BrokerId

/**
 * Decorator that logs execution attempts for paper/hybrid modes while delegating to a live manager.
 */
class LoggingExecutionManager(
    private val delegate: ExecutionManager,
    private val brokerId: BrokerId
) : ExecutionManager by delegate {

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan): BracketPlacementResult {
        VolumeExhaustionLog.executionAttempt(brokerId, plan.symbol, "placeTouchTurnBracket")
        val result = delegate.placeTouchTurnBracket(plan)
        VolumeExhaustionLog.executionResult(
            brokerId,
            plan.symbol,
            "placed entryOrderId=${result.entryOrderId ?: "pending"}"
        )
        return result
    }

    override suspend fun cancelOrder(orderId: Int): Boolean {
        VolumeExhaustionLog.executionAttempt(brokerId, orderId = orderId, action = "cancelOrder")
        val cancelled = delegate.cancelOrder(orderId)
        VolumeExhaustionLog.executionResult(brokerId, orderId = orderId, detail = "cancelled=$cancelled")
        return cancelled
    }
}
