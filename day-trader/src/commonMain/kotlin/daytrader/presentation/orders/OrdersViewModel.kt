package daytrader.presentation.orders

import daytrader.broker.SymbolMarkets
import daytrader.data.OpenOrderRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.data.WatchlistRepository
import daytrader.domain.BracketAmendTarget
import daytrader.domain.StrategyDeployment
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistPlanOrderLinks
import daytrader.engine.liquidity.TouchTurnBracketAmendResult
import daytrader.engine.liquidity.TouchTurnBracketResizer
import daytrader.execution.ExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.WorkingOrder
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.strategies.TouchTurnBracketAmendUiMapper
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
    private val deploymentRepository: StrategyDeploymentRepository? = null,
    private val executionManager: ExecutionManager? = null,
    private val executionGateway: BrokerGateway? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.ORDERS, "OrdersViewModel"),
) {
    private val scope = scope

    private var openOrders: List<WorkingOrder> = emptyList()
    private var deployments: List<StrategyDeployment> = emptyList()
    private var watchlists: List<Watchlist> = emptyList()
    private var sortColumn = OrderSortColumn.SYMBOL
    private var sortDirection = SortDirection.ASCENDING
    private var expandedSymbolKey: String? = null
    private var connectionState: GatewayConnectionState = GatewayConnectionState.Disconnected
    private val cancellingOrderIds = mutableSetOf<Int>()
    private var cancelMessage: String? = null
    private var amendDialogSymbolKey: String? = null
    private var amendFeedbackMessage: String? = null
    private val bracketAmendingKeys = mutableSetOf<String>()
    private val bracketAmendErrors = mutableMapOf<String, String>()
    private val bracketAmendSuccess = mutableMapOf<String, String>()
    private val bracketResizer = executionManager?.let { execution ->
        deploymentRepository?.let { deployments ->
            TouchTurnBracketResizer(
                executionManager = execution,
                deploymentRepository = deployments,
                watchlistRepository = watchlistRepository,
            )
        }
    }

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

        deploymentRepository?.deployments
            ?.onEach { items ->
                deployments = items
                emitUiState()
            }
            ?.launchIn(scope)

        executionGateway?.connectionState
            ?.onEach { state ->
                connectionState = state
                if (state != GatewayConnectionState.Connected) {
                    cancellingOrderIds.clear()
                    bracketAmendingKeys.clear()
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

    fun onAmendBracketClick(symbolKey: String) {
        if (bracketResizer == null) return
        amendDialogSymbolKey = symbolKey
        emitUiState()
    }

    fun onDismissAmendDialog() {
        amendDialogSymbolKey = null
        emitUiState()
    }

    fun onDismissAmendFeedback() {
        amendFeedbackMessage = null
        emitUiState()
    }

    fun onAmendBracket(target: BracketAmendTarget, targetQuantityText: String) {
        val resizer = bracketResizer ?: return
        val amendKey = target.amendKey
        val targetQuantity = targetQuantityText.filter { it.isDigit() }.toIntOrNull() ?: return
        if (connectionState != GatewayConnectionState.Connected) {
            bracketAmendErrors[amendKey] = "Connect to your broker to amend orders."
            emitUiState()
            return
        }
        scope.launchUiAction(AppScreen.ORDERS, "onAmendBracket") {
            bracketAmendingKeys.add(amendKey)
            bracketAmendErrors.remove(amendKey)
            bracketAmendSuccess.remove(amendKey)
            amendFeedbackMessage = null
            emitUiState()

            val result = resizer.amend(
                target = target,
                openOrders = openOrders,
                targetQuantity = targetQuantity,
            )
            bracketAmendingKeys.remove(amendKey)
            when (result) {
                is TouchTurnBracketAmendResult.Success -> {
                    bracketAmendErrors.remove(amendKey)
                    val message = "Bracket amended to ${result.newQuantity} shares. Confirm in TWS/open orders."
                    bracketAmendSuccess[amendKey] = message
                    amendFeedbackMessage = message
                }
                is TouchTurnBracketAmendResult.Skipped ->
                    bracketAmendErrors[amendKey] = humanizeBracketAmendError(result.reason)
                is TouchTurnBracketAmendResult.Failed ->
                    bracketAmendErrors[amendKey] = humanizeBracketAmendError(result.message)
            }
            emitUiState()
        }
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
            val bracketAmend = TouchTurnBracketAmendUiMapper.resolveForOrderGroup(
                symbolKey = symbolKey,
                groupOrders = orders,
                deployments = deployments,
                watchlists = watchlists,
                allOpenOrders = working,
                isApplying = { amendKey -> amendKey in bracketAmendingKeys },
                errorFor = { amendKey -> bracketAmendErrors[amendKey] },
                successFor = { amendKey -> bracketAmendSuccess[amendKey] },
            )
            OrderSymbolGroupUi(
                symbolKey = symbolKey,
                displaySymbol = displaySymbol,
                orders = orders.map { order ->
                    OpenOrderUiMapper.toRowUi(order, planLabels[order.orderId]).let { row ->
                        row.copy(isCancelling = row.canCancel && order.orderId in cancellingOrderIds)
                    }
                },
                isExpanded = expandedSymbolKey == symbolKey,
                bracketAmend = bracketAmend,
            )
        }
        val canAmendBrackets = bracketResizer != null && connectionState == GatewayConnectionState.Connected
        _uiState.update {
            OrdersUiState(
                groups = groups,
                totalOrderCount = working.size,
                sortColumn = sortColumn,
                sortDirection = sortDirection,
                brokerLabel = brokerKind.displayName,
                canCancelOrders = executionGateway != null && connectionState == GatewayConnectionState.Connected,
                canAmendBrackets = canAmendBrackets,
                cancelMessage = cancelMessage,
                amendDialogSymbolKey = amendDialogSymbolKey,
                amendFeedbackMessage = amendFeedbackMessage,
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

    private fun humanizeBracketAmendError(raw: String): String = when {
        raw.contains("bracket_ack_timeout") ->
            "IB did not confirm the amended quantity. Check TWS — if qty unchanged, the modify was rejected."
        raw.contains("bracket_resize_missing_perm_id") ->
            "Open orders are still loading. Wait a few seconds and try again."
        raw.contains("entry_already_filled") ->
            "Entry leg already has fills — bracket can only be upsized while unfilled."
        else -> raw
    }
}
