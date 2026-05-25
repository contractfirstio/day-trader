package daytrader.gateway

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
}
