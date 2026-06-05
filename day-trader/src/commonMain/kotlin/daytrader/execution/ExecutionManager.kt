package daytrader.execution

import daytrader.domain.TouchTurnOrderPlan
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.flow.StateFlow

/** Result of a bracket submission — entry order id used for buffer-zone cancellation. */
data class BracketPlacementResult(
    val entryOrderId: Int?,
    val plan: TouchTurnOrderPlan
)

/**
 * Uniform execution surface for Touch Turn (live IB vs paper/hybrid).
 */
interface ExecutionManager {
    val openOrders: StateFlow<List<WorkingOrder>>
    val positions: StateFlow<List<AccountPosition>>
    val fills: StateFlow<List<BrokerFill>>

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan): BracketPlacementResult

    suspend fun cancelOrder(orderId: Int): Boolean

    /** Wait until the broker reports a submitted/working state before sending cancel. */
    suspend fun awaitOrderSubmitted(orderId: Int, timeoutMs: Long = 15_000L): Boolean

    fun cancelOpenOrdersForSymbol(symbol: String)

    fun flattenSymbolForSymbol(symbol: String)

    fun refreshFills()
}
