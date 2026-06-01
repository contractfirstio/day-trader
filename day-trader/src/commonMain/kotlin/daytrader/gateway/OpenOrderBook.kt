package daytrader.gateway

/**
 * Working-order book updated the same way IB TWS callbacks do: per-order open/status events
 * that culminate in full [snapshot] publishes.
 */
class OpenOrderBook {
    private val ordersById = linkedMapOf<Int, WorkingOrder>()

    fun applyOpenOrder(order: WorkingOrder) {
        if (isTerminalStatus(order.status)) {
            ordersById.remove(order.orderId)
            return
        }
        if (order.remaining <= 0) {
            ordersById.remove(order.orderId)
            return
        }
        ordersById[order.orderId] = order
    }

    fun applyOrderStatus(
        orderId: Int,
        status: String,
        filled: Int,
        remaining: Int,
        permId: Long = 0L,
        parentOrderId: Int? = null
    ) {
        if (isTerminalStatus(status)) {
            ordersById.remove(orderId)
            return
        }
        val existing = ordersById[orderId] ?: return
        if (remaining <= 0) {
            ordersById.remove(orderId)
            return
        }
        ordersById[orderId] = existing.copy(
            status = status,
            filled = filled,
            remaining = remaining,
            permId = permId.takeIf { it > 0 } ?: existing.permId,
            parentOrderId = parentOrderId?.takeIf { it > 0 } ?: existing.parentOrderId
        )
    }

    fun removeOrder(orderId: Int) {
        ordersById.remove(orderId)
    }

    fun clear() {
        ordersById.clear()
    }

    fun snapshot(): List<WorkingOrder> = ordersById.values.sortedBy { it.orderId }

    companion object {
        fun isTerminalStatus(status: String): Boolean =
            status.equals("Filled", ignoreCase = true) ||
                status.equals("Cancelled", ignoreCase = true) ||
                status.equals("ApiCancelled", ignoreCase = true) ||
                status.equals("Inactive", ignoreCase = true)
    }
}
