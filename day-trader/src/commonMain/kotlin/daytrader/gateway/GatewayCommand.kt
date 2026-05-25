package daytrader.gateway

import daytrader.domain.TouchTurnOrderPlan

sealed interface GatewayCommand {
    data object Connect : GatewayCommand

    data object Disconnect : GatewayCommand

    data object Reconnect : GatewayCommand

    data object Shutdown : GatewayCommand

    data class FetchFirstFifteenMinuteCandle(
        val requestId: Long,
        val symbol: String
    ) : GatewayCommand

    data class FetchFourteenDayAdr(
        val requestId: Long,
        val symbol: String
    ) : GatewayCommand

    data class PlaceTouchTurnBracket(val plan: TouchTurnOrderPlan) : GatewayCommand

    /** Cancel all non-terminal working orders for [symbol] (session stop cleanup). */
    data class CancelOpenOrdersForSymbol(val symbol: String) : GatewayCommand

    /** Market-close an open position for [symbol] if quantity is non-zero (session stop cleanup). */
    data class CloseOpenPositionForSymbol(val symbol: String) : GatewayCommand

    /** Cancel working orders and close any open position for [symbol] (session stop). */
    data class FlattenSymbolForSymbol(val symbol: String) : GatewayCommand

    /** Reload executions/fills from the broker (IB: [reqExecutions]). */
    data object RequestExecutions : GatewayCommand
}
