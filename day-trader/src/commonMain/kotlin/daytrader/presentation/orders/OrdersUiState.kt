package daytrader.presentation.orders

import daytrader.presentation.positions.SortDirection

enum class OrderSortColumn {
    SYMBOL,
    ACTION,
    TYPE,
    QUANTITY,
    PRICE,
    STATUS,
    ORDER_ID
}

data class OpenOrderRowUi(
    val orderId: Int,
    val parentOrderId: Int,
    val symbol: String,
    val action: String,
    val orderType: String,
    val quantityLabel: String,
    val priceLabel: String,
    val status: String,
    val legLabel: String,
    val sourcePlanLabel: String? = null
)

data class OrderSymbolGroupSummaryUi(
    val orderCountLabel: String,
    val actionLabel: String,
    val typeLabel: String,
    val legLabel: String,
    val quantityLabel: String,
    val priceLabel: String,
    val statusLabel: String
)

data class OrderSymbolGroupUi(
    val symbolKey: String,
    val displaySymbol: String,
    val orders: List<OpenOrderRowUi>,
    val isExpanded: Boolean
) {
    val isGrouped: Boolean get() = orders.size > 1
}

data class OrdersUiState(
    val groups: List<OrderSymbolGroupUi> = emptyList(),
    val totalOrderCount: Int = 0,
    val sortColumn: OrderSortColumn = OrderSortColumn.SYMBOL,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val brokerLabel: String = "Broker"
)
