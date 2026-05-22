package daytrader.broker

sealed interface IbConnectionState {
    data object Disconnected : IbConnectionState

    data object Connecting : IbConnectionState

    data class Connected(val nextOrderId: Int) : IbConnectionState

    data class Error(val message: String) : IbConnectionState
}
