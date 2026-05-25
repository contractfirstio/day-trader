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

    /** Emulator places working bracket legs; IB adapter ignores until live placement exists. */
    data class PlaceTouchTurnBracket(val plan: TouchTurnOrderPlan) : GatewayCommand
}
