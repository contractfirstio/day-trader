package daytrader.presentation.orders

import daytrader.presentation.positions.SortDirection
import daytrader.presentation.strategies.TouchTurnBracketAmendUiState

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
    val sourcePlanLabel: String? = null,
    val canCancel: Boolean = true,
    val isCancelling: Boolean = false
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
    val isExpanded: Boolean,
    val bracketAmend: TouchTurnBracketAmendUiState? = null,
) {
    val isGrouped: Boolean get() = orders.size > 1
}

data class OrdersUiState(
    val groups: List<OrderSymbolGroupUi> = emptyList(),
    val totalOrderCount: Int = 0,
    val sortColumn: OrderSortColumn = OrderSortColumn.SYMBOL,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val brokerLabel: String = "Broker",
    val canCancelOrders: Boolean = false,
    val canAmendBrackets: Boolean = false,
    val cancelMessage: String? = null,
    val amendDialogSymbolKey: String? = null,
    val amendFeedbackMessage: String? = null,
)
