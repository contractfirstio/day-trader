package daytrader.gateway

import daytrader.domain.OhlcBar

sealed interface GatewayEvent {
    data class ConnectionStateChanged(val state: GatewayConnectionState) : GatewayEvent

    data class PositionsSnapshot(val positions: List<AccountPosition>) : GatewayEvent

    data class OpenOrdersSnapshot(val orders: List<WorkingOrder>) : GatewayEvent

    data class FirstFifteenMinuteCandleReady(
        val requestId: Long,
        val result: Result<OhlcBar>
    ) : GatewayEvent

    data class FourteenDayAdrReady(
        val requestId: Long,
        val result: Result<Double>
    ) : GatewayEvent
}
