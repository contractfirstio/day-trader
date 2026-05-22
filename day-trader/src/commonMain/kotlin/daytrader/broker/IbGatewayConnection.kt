package daytrader.broker

import kotlinx.coroutines.flow.StateFlow

interface IbGatewayConnection : FirstFifteenMinuteCandleProvider, FourteenDayAdrProvider {
    val state: StateFlow<IbConnectionState>

    val positions: StateFlow<List<BrokerPosition>>

    val openOrders: StateFlow<List<BrokerOpenOrder>>

    fun connect()

    fun disconnect()

    fun reconnect()
}
