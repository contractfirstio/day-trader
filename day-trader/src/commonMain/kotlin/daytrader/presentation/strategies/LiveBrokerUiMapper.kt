package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.gateway.AccountPosition
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.WorkingOrder
import daytrader.presentation.Formatters

data class LivePositionUi(
    val symbol: String,
    val companyName: String,
    val sideLabel: String,
    val quantity: Int,
    val formattedAvgPrice: String,
    val formattedMarketPrice: String,
    val formattedUnrealizedPnL: String,
    val unrealizedPnL: Double,
    val isPositivePnL: Boolean,
    val formattedDailyChange: String?
)

data class LiveOpenOrderUi(
    val orderId: Int,
    val permId: Long = 0L,
    val action: String,
    val summary: String,
    val status: String
)

data class LiveBrokerUiState(
    val symbol: String,
    val isConnected: Boolean,
    val statusMessage: String?,
    val position: LivePositionUi?,
    val openOrders: List<LiveOpenOrderUi>
)

object LiveBrokerUiMapper {
    fun forSymbol(
        symbol: String,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
        connection: GatewayConnectionState
    ): LiveBrokerUiState {
        val isConnected = connection is GatewayConnectionState.Connected
        val position = positions
            .firstOrNull { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
            ?.let(::toPositionUi)
        val orders = openOrders
            .filter { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
            .sortedBy { it.orderId }
            .map(::toOrderUi)

        val statusMessage = when (connection) {
            GatewayConnectionState.Disconnected -> "Connect to your broker to load position and orders."
            GatewayConnectionState.Connecting -> "Loading position and orders from broker…"
            is GatewayConnectionState.Error -> "Broker unavailable — ${connection.message}"
            GatewayConnectionState.Connected -> null
        }

        return LiveBrokerUiState(
            symbol = symbol,
            isConnected = isConnected,
            statusMessage = statusMessage,
            position = position,
            openOrders = orders
        )
    }

    fun positionUi(account: AccountPosition): LivePositionUi = toPositionUi(account)

    private fun toPositionUi(account: AccountPosition): LivePositionUi {
        val side = when {
            account.quantity > 0 -> "Long"
            account.quantity < 0 -> "Short"
            else -> "Flat"
        }
        val daily = account.priorClose?.let { close ->
            if (close > 0) Formatters.percent(account.dailyChangePct) else null
        }
        return LivePositionUi(
            symbol = account.symbol,
            companyName = account.companyName,
            sideLabel = side,
            quantity = kotlin.math.abs(account.quantity),
            formattedAvgPrice = Formatters.moneyPlain(account.avgPrice, account.currency),
            formattedMarketPrice = Formatters.moneyPlain(account.marketPrice, account.currency),
            formattedUnrealizedPnL = Formatters.money(account.totalUnrealizedPnL, account.currency, showSign = true),
            unrealizedPnL = account.totalUnrealizedPnL,
            isPositivePnL = account.totalUnrealizedPnL >= 0,
            formattedDailyChange = daily
        )
    }

    private fun toOrderUi(order: WorkingOrder): LiveOpenOrderUi {
        val price = orderPriceLabel(order)
        val qty = if (order.filled > 0) {
            "${order.filled}/${order.quantity} filled"
        } else {
            order.remaining.toString()
        }
        val summary = buildString {
            append(order.action)
            append(" ")
            append(qty)
            append(" · ")
            append(order.orderType)
            if (price != null) {
                append(" @ ")
                append(price)
            }
        }
        return LiveOpenOrderUi(
            orderId = order.orderId,
            permId = order.permId,
            action = order.action,
            summary = summary,
            status = order.status
        )
    }

    private fun orderPriceLabel(order: WorkingOrder): String? {
        val currency = order.currency
        return when {
            order.limitPrice != null && order.limitPrice > 0 ->
                Formatters.moneyPlain(order.limitPrice, currency)
            order.stopPrice != null && order.stopPrice > 0 ->
                "stop ${Formatters.moneyPlain(order.stopPrice, currency)}"
            else -> null
        }
    }
}
