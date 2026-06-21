package daytrader.presentation.orders

import daytrader.broker.SymbolMarkets
import daytrader.data.OpenOrderRepository
import daytrader.data.WatchlistRepository
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistPlanOrderLinks
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.WorkingOrder
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.launchUiAction
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
    private val executionGateway: BrokerGateway? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.ORDERS, "OrdersViewModel"),
) {
    private val scope = scope

    private var openOrders: List<WorkingOrder> = emptyList()
    private var watchlists: List<Watchlist> = emptyList()
    private var sortColumn = OrderSortColumn.SYMBOL
    private var sortDirection = SortDirection.ASCENDING
    private var expandedSymbolKey: String? = null
    private var connectionState: GatewayConnectionState = GatewayConnectionState.Disconnected
    private val cancellingOrderIds = mutableSetOf<Int>()
    private var cancelMessage: String? = null

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

        executionGateway?.connectionState
            ?.onEach { state ->
                connectionState = state
                if (state != GatewayConnectionState.Connected) {
                    cancellingOrderIds.clear()
                }
                emitUiState()
            }
            ?.launchIn(scope)
    }

    fun onCancelOrder(orderId: Int) {
        if (executionGateway == null) return
        if (connectionState != GatewayConnectionState.Connected) {
            cancelMessage = "Connect to your broker to cancel orders."
            emitUiState()
            return
        }
        if (orderId in cancellingOrderIds) return
        cancellingOrderIds.add(orderId)
        cancelMessage = null
        emitUiState()
        scope.launchUiAction(AppScreen.ORDERS, "onCancelOrder") {
            executionGateway.cancelOrder(orderId)
            cancellingOrderIds.remove(orderId)
            emitUiState()
        }
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
            compareRepresentatives(
                representativeOrder(bySymbol[leftKey].orEmpty()),
                representativeOrder(bySymbol[rightKey].orEmpty()),
            )
        }
        val planLabels = WatchlistPlanOrderLinks.enrichWithPlanLabels(working, watchlists)
        val groups = sortedKeys.mapNotNull { symbolKey ->
            val orders = bySymbol[symbolKey].orEmpty()
                .sortedWith(
                    compareBy<WorkingOrder> { it.parentOrderId != 0 }
                        .thenBy { it.orderId }
                )
            val displaySymbol = orders.firstOrNull()?.symbol ?: return@mapNotNull null
            OrderSymbolGroupUi(
                symbolKey = symbolKey,
                displaySymbol = displaySymbol,
                orders = orders.map { order ->
                    OpenOrderUiMapper.toRowUi(order, planLabels[order.orderId]).let { row ->
                        row.copy(isCancelling = row.canCancel && order.orderId in cancellingOrderIds)
                    }
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
                brokerLabel = brokerKind.displayName,
                canCancelOrders = executionGateway != null && connectionState == GatewayConnectionState.Connected,
                cancelMessage = cancelMessage
            )
        }
    }

    private fun representativeOrder(orders: List<WorkingOrder>): WorkingOrder? =
        orders.minByOrNull { it.orderId }

    private fun compareRepresentatives(left: WorkingOrder?, right: WorkingOrder?): Int {
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
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
