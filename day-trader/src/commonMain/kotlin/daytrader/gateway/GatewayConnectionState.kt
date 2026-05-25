package daytrader.gateway

sealed interface GatewayConnectionState {
    data object Disconnected : GatewayConnectionState

    data object Connecting : GatewayConnectionState

    data object Connected : GatewayConnectionState

    data class Error(val message: String) : GatewayConnectionState
}
