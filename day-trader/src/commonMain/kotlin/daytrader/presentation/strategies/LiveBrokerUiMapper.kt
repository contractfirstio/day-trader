package daytrader.presentation.strategies

import daytrader.broker.BrokerOpenOrder
import daytrader.broker.BrokerPosition
import daytrader.broker.IbConnectionState
import daytrader.broker.SymbolMarkets
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
        positions: List<BrokerPosition>,
        openOrders: List<BrokerOpenOrder>,
        connection: IbConnectionState
    ): LiveBrokerUiState {
        val isConnected = connection is IbConnectionState.Connected
        val position = positions
            .firstOrNull { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
            ?.let(::toPositionUi)
        val orders = openOrders
            .filter { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
            .sortedBy { it.orderId }
            .map(::toOrderUi)

        val statusMessage = when (connection) {
            IbConnectionState.Disconnected -> "Connect to IB Gateway to load position and orders."
            IbConnectionState.Connecting -> "Loading position and orders from IB…"
            is IbConnectionState.Error -> "IB unavailable — ${connection.message}"
            is IbConnectionState.Connected -> null
        }

        return LiveBrokerUiState(
            symbol = symbol,
            isConnected = isConnected,
            statusMessage = statusMessage,
            position = position,
            openOrders = orders
        )
    }

    fun positionUi(broker: BrokerPosition): LivePositionUi = toPositionUi(broker)

    private fun toPositionUi(broker: BrokerPosition): LivePositionUi {
        val side = when {
            broker.quantity > 0 -> "Long"
            broker.quantity < 0 -> "Short"
            else -> "Flat"
        }
        val daily = broker.priorClose?.let { close ->
            if (close > 0) Formatters.percent(broker.dailyChangePct) else null
        }
        return LivePositionUi(
            symbol = broker.symbol,
            companyName = broker.companyName,
            sideLabel = side,
            quantity = kotlin.math.abs(broker.quantity),
            formattedAvgPrice = Formatters.moneyPlain(broker.avgPrice, broker.currency),
            formattedMarketPrice = Formatters.moneyPlain(broker.marketPrice, broker.currency),
            formattedUnrealizedPnL = Formatters.money(broker.totalUnrealizedPnL, broker.currency, showSign = true),
            unrealizedPnL = broker.totalUnrealizedPnL,
            isPositivePnL = broker.totalUnrealizedPnL >= 0,
            formattedDailyChange = daily
        )
    }

    private fun toOrderUi(order: BrokerOpenOrder): LiveOpenOrderUi {
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
            action = order.action,
            summary = summary,
            status = order.status
        )
    }

    private fun orderPriceLabel(order: BrokerOpenOrder): String? {
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
