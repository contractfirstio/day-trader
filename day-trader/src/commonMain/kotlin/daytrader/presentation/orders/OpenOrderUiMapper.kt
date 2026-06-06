package daytrader.presentation.orders

import daytrader.broker.SymbolMarkets
import daytrader.gateway.OpenOrderBook
import daytrader.gateway.WorkingOrder
import daytrader.presentation.Formatters

object OpenOrderUiMapper {
    fun toRowUi(order: WorkingOrder, sourcePlanLabel: String? = null): OpenOrderRowUi {
        val priceLabel = orderPriceLabel(order).orEmpty()
        val quantityLabel = if (order.filled > 0) {
            "${order.filled}/${order.quantity}"
        } else {
            order.remaining.toString()
        }
        return OpenOrderRowUi(
            orderId = order.orderId,
            parentOrderId = order.parentOrderId,
            symbol = order.symbol,
            action = order.action,
            orderType = order.orderType,
            quantityLabel = quantityLabel,
            priceLabel = priceLabel,
            status = order.status,
            legLabel = legLabel(order),
            sourcePlanLabel = sourcePlanLabel
        )
    }

    fun workingOrders(orders: List<WorkingOrder>): List<WorkingOrder> =
        orders.filter { order ->
            order.remaining > 0 && !OpenOrderBook.isTerminalStatus(order.status)
        }

    fun buildSymbolGroups(
        rows: List<OpenOrderRowUi>,
        expandedSymbolKey: String?
    ): List<OrderSymbolGroupUi> =
        rows.groupBy { SymbolMarkets.normalizeSymbol(it.symbol) }
            .map { (symbolKey, orders) ->
                OrderSymbolGroupUi(
                    symbolKey = symbolKey,
                    displaySymbol = orders.first().symbol,
                    orders = orders.sortedWith(
                        compareBy<OpenOrderRowUi> { it.parentOrderId != 0 }
                            .thenBy { it.orderId }
                    ),
                    isExpanded = expandedSymbolKey == symbolKey
                )
            }

    fun collapsedSummary(group: OrderSymbolGroupUi): OrderSymbolGroupSummaryUi {
        val orders = group.orders
        val actions = orders.map { it.action }.distinct()
        val statuses = orders.map { it.status }.distinct()
        val entry = orders.firstOrNull { it.parentOrderId == 0 }
        return OrderSymbolGroupSummaryUi(
            orderCountLabel = "${orders.size} orders",
            actionLabel = if (actions.size == 1) actions.single() else "Mixed",
            typeLabel = if (orders.any { it.parentOrderId != 0 }) "Bracket" else "Multiple",
            legLabel = "${orders.size} legs",
            quantityLabel = entry?.quantityLabel ?: "—",
            priceLabel = entry?.priceLabel?.ifBlank { "—" } ?: "—",
            statusLabel = if (statuses.size == 1) statuses.single() else "${statuses.size} statuses"
        )
    }

    private fun legLabel(order: WorkingOrder): String = when {
        order.parentOrderId == 0 -> "Entry"
        order.orderType.equals("STP", ignoreCase = true) ||
            order.orderType.equals("STP LMT", ignoreCase = true) -> "Stop"
        else -> "Target"
    }

    private fun orderPriceLabel(order: WorkingOrder): String? = when {
        order.limitPrice != null && order.limitPrice > 0 ->
            Formatters.moneyPlain(order.limitPrice, order.currency)
        order.stopPrice != null && order.stopPrice > 0 ->
            "stop ${Formatters.moneyPlain(order.stopPrice, order.currency)}"
        else -> null
    }
}
