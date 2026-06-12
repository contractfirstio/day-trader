package daytrader.execution

import daytrader.broker.SymbolMarkets
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes Touch Turn execution through [BrokerGateway].
 */
class BrokerGatewayExecutionManager(
    private val gateway: BrokerGateway
) : ExecutionManager {

    override val openOrders: StateFlow<List<WorkingOrder>> = gateway.openOrders
    override val positions = gateway.positions
    override val fills = gateway.fills

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan): BracketPlacementResult {
        val beforeIds = openOrders.value.map { it.orderId }.toSet()
        gateway.placeTouchTurnBracket(plan)
        val entryOrderId = resolveEntryOrderId(plan, beforeIds)
        return BracketPlacementResult(entryOrderId = entryOrderId, plan = plan)
    }

    override suspend fun resizeTouchTurnBracket(request: TouchTurnBracketResizeRequest): Result<Unit> =
        gateway.resizeTouchTurnBracket(request)

    override suspend fun cancelOrder(orderId: Int): Boolean {
        if (!awaitOrderSubmitted(orderId)) return false
        gateway.cancelOrder(orderId)
        return true
    }

    override suspend fun awaitOrderSubmitted(orderId: Int, timeoutMs: Long): Boolean {
        val submittedStatuses = setOf(
            "Submitted",
            "PreSubmitted",
            "PendingSubmit",
            "ApiPending"
        )
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val order = openOrders.value.find { it.orderId == orderId }
                if (order != null && order.status in submittedStatuses) return@withTimeoutOrNull true
                if (order != null && order.status in TERMINAL_STATUSES) return@withTimeoutOrNull false
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    override fun cancelOpenOrdersForSymbol(symbol: String) {
        gateway.cancelOpenOrdersForSymbol(symbol)
    }

    override fun flattenSymbolForSymbol(symbol: String) {
        gateway.flattenSymbolForSymbol(symbol)
    }

    override fun refreshFills() {
        gateway.refreshFills()
    }

    private fun resolveEntryOrderId(plan: TouchTurnOrderPlan, beforeIds: Set<Int>): Int? {
        val entryLeg = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY } ?: return null
        val newOrders = openOrders.value.filter { it.orderId !in beforeIds }
        val match = newOrders.firstOrNull { order ->
            SymbolMarkets.symbolsMatch(order.symbol, plan.symbol) &&
                order.limitPrice == entryLeg.price
        }
        if (match != null) return match.orderId
        if (gateway.brokerId == BrokerId.EMULATOR) {
            return openOrders.value
                .filter { SymbolMarkets.symbolsMatch(it.symbol, plan.symbol) }
                .maxByOrNull { it.orderId }
                ?.orderId
        }
        return null
    }

    private companion object {
        val TERMINAL_STATUSES = setOf("Filled", "Cancelled", "Inactive", "ApiCancelled")
    }
}
