package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.gateway.AccountPosition
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
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
    val bid: Double?,
    val ask: Double?,
    val last: Double?,
    val formattedBid: String?,
    val formattedAsk: String?,
    val formattedLast: String?,
    val fillReadinessHint: String? = null,
    val position: LivePositionUi?,
    val openOrders: List<LiveOpenOrderUi>
)

object LiveBrokerUiMapper {
    private val livePriceUiLogsEnabled: Boolean =
        System.getenv("DAY_TRADER_LIVE_PRICE_UI_LOGS")?.equals("true", ignoreCase = true) == true

    fun forSymbol(
        symbol: String,
        positions: List<AccountPosition>,
        quotes: Map<String, LiveQuote>,
        openOrders: List<WorkingOrder>,
        connection: GatewayConnectionState,
        includeMarketQuotes: Boolean = true,
        requireBidAskForFills: Boolean = false,
    ): LiveBrokerUiState {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val quote = quotes[norm]
        val hasLiveQuote = quote != null &&
            (quote.bid != null || quote.ask != null || quote.last != null)
        val isConnected = connection is GatewayConnectionState.Connected || hasLiveQuote
        val accountPosition = positions
            .firstOrNull { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
        val position = accountPosition?.let { toPositionUi(it, includeMarketQuotes) }
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

        val currency = accountPosition?.currency ?: SymbolMarkets.currencyCode(symbol)
        val bid = if (includeMarketQuotes) {
            accountPosition?.bidPrice?.takeIf { it > 0.0 }
                ?: quote?.bid?.takeIf { it > 0.0 }
        } else {
            null
        }
        val ask = if (includeMarketQuotes) {
            accountPosition?.askPrice?.takeIf { it > 0.0 }
                ?: quote?.ask?.takeIf { it > 0.0 }
        } else {
            null
        }
        val last = if (includeMarketQuotes) {
            accountPosition?.lastTradePrice?.takeIf { it > 0.0 }
                ?: accountPosition?.marketPrice?.takeIf { it > 0.0 }
                ?: quote?.last?.takeIf { it > 0.0 }
        } else {
            null
        }
        val formattedBid = bid?.let { Formatters.moneyPlain(it, currency) }
        val formattedAsk = ask?.let { Formatters.moneyPlain(it, currency) }
        val formattedLast = last?.let { Formatters.moneyPlain(it, currency) }

        if (livePriceUiLogsEnabled) {
            TimestampedConsoleLog.line(
                "LIVE_PRICE_UI",
                "symbol=$symbol connected=$isConnected " +
                    "posFound=${accountPosition != null} quoteFound=${quote != null} " +
                    "bid=${accountPosition?.bidPrice ?: quote?.bid} ask=${accountPosition?.askPrice ?: quote?.ask} " +
                    "last=${accountPosition?.lastTradePrice ?: quote?.last} mkt=${accountPosition?.marketPrice} " +
                    "formattedBid=$formattedBid formattedAsk=$formattedAsk formattedLast=$formattedLast"
            )
        }

        return LiveBrokerUiState(
            symbol = symbol,
            isConnected = isConnected,
            statusMessage = statusMessage,
            bid = bid,
            ask = ask,
            last = last,
            formattedBid = formattedBid,
            formattedAsk = formattedAsk,
            formattedLast = formattedLast,
            fillReadinessHint = LiveMarkPriceResolver.fillReadinessHint(quote, requireBidAskForFills),
            position = position,
            openOrders = orders
        )
    }

    fun positionUi(account: AccountPosition, includeMarketQuotes: Boolean = true): LivePositionUi =
        toPositionUi(account, includeMarketQuotes)

    private fun toPositionUi(account: AccountPosition, includeMarketQuotes: Boolean = true): LivePositionUi {
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
            formattedMarketPrice = if (includeMarketQuotes) {
                Formatters.moneyPlain(account.marketPrice, account.currency)
            } else {
                "—"
            },
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
