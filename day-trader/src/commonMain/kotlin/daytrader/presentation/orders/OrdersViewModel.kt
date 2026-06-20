package daytrader.presentation.orders

import daytrader.broker.SymbolMarkets
import daytrader.data.OpenOrderRepository
import daytrader.data.WatchlistRepository
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistPlanOrderLinks
import daytrader.gateway.BrokerKind
import daytrader.gateway.WorkingOrder
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.safeUiEmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class OrdersViewModel(
    private val repository: OpenOrderRepository,
    private val watchlistRepository: WatchlistRepository? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.ORDERS, "OrdersViewModel"),
) {
    private val scope = scope

    private var openOrders: List<WorkingOrder> = emptyList()
    private var watchlists: List<Watchlist> = emptyList()
    private var sortColumn = OrderSortColumn.SYMBOL
    private var sortDirection = SortDirection.ASCENDING
    private var expandedSymbolKey: String? = null

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    init {
        repository.openOrders
            .onEach { orders ->
                openOrders = orders
                emitUiState()
            }
            .launchIn(scope)

        watchlistRepository?.watchlists
            ?.onEach { lists ->
                watchlists = lists
                emitUiState()
            }
            ?.launchIn(scope)
    }

    fun onHeaderClick(column: OrderSortColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASCENDING) {
                SortDirection.DESCENDING
            } else {
                SortDirection.ASCENDING
            }
        } else {
            sortColumn = column
            sortDirection = SortDirection.ASCENDING
        }
        emitUiState()
    }

    fun onSymbolGroupClick(symbolKey: String) {
        expandedSymbolKey = if (expandedSymbolKey == symbolKey) null else symbolKey
        emitUiState()
    }

    private fun emitUiState() {
        safeUiEmit(AppScreen.ORDERS, "emitUiState") {
            emitUiStateInternal()
        }
    }

    private fun emitUiStateInternal() {
        val working = OpenOrderUiMapper.workingOrders(openOrders)
        val activeKeys = working.map { SymbolMarkets.normalizeSymbol(it.symbol) }.toSet()
        if (expandedSymbolKey != null && expandedSymbolKey !in activeKeys) {
            expandedSymbolKey = null
        }
        val bySymbol = working.groupBy { SymbolMarkets.normalizeSymbol(it.symbol) }
        val sortedKeys = bySymbol.keys.sortedWith { leftKey, rightKey ->
            val leftRep = representativeOrder(bySymbol[leftKey].orEmpty())
            val rightRep = representativeOrder(bySymbol[rightKey].orEmpty())
            compareRepresentatives(leftRep, rightRep)
        }
        val planLabels = WatchlistPlanOrderLinks.enrichWithPlanLabels(working, watchlists)
        val groups = sortedKeys.map { symbolKey ->
            val orders = bySymbol[symbolKey].orEmpty()
                .sortedWith(
                    compareBy<WorkingOrder> { it.parentOrderId != 0 }
                        .thenBy { it.orderId }
                )
            OrderSymbolGroupUi(
                symbolKey = symbolKey,
                displaySymbol = orders.first().symbol,
                orders = orders.map { order ->
                    OpenOrderUiMapper.toRowUi(order, planLabels[order.orderId])
                },
                isExpanded = expandedSymbolKey == symbolKey
            )
        }
        _uiState.update {
            OrdersUiState(
                groups = groups,
                totalOrderCount = working.size,
                sortColumn = sortColumn,
                sortDirection = sortDirection,
                brokerLabel = brokerKind.displayName
            )
        }
    }

    private fun representativeOrder(orders: List<WorkingOrder>): WorkingOrder =
        orders.minByOrNull { it.orderId } ?: error("empty group")

    private fun compareRepresentatives(left: WorkingOrder, right: WorkingOrder): Int {
        val result = when (sortColumn) {
            OrderSortColumn.SYMBOL ->
                SymbolMarkets.normalizeSymbol(left.symbol).compareTo(SymbolMarkets.normalizeSymbol(right.symbol))
            OrderSortColumn.ACTION -> left.action.compareTo(right.action)
            OrderSortColumn.TYPE -> left.orderType.compareTo(right.orderType)
            OrderSortColumn.QUANTITY -> left.remaining.compareTo(right.remaining)
            OrderSortColumn.PRICE -> priceSortKey(left).compareTo(priceSortKey(right))
            OrderSortColumn.STATUS -> left.status.compareTo(right.status)
            OrderSortColumn.ORDER_ID -> left.orderId.compareTo(right.orderId)
        }
        return if (sortDirection == SortDirection.DESCENDING) -result else result
    }

    private fun priceSortKey(order: WorkingOrder): Double =
        order.limitPrice?.takeIf { it > 0.0 }
            ?: order.stopPrice?.takeIf { it > 0.0 }
            ?: 0.0
}
