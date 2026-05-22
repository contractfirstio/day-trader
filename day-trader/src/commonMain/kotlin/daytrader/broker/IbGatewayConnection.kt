package daytrader.broker

import kotlinx.coroutines.flow.StateFlow

interface IbGatewayConnection {
    val state: StateFlow<IbConnectionState>

    val positions: StateFlow<List<BrokerPosition>>

    fun connect()

    fun disconnect()

    fun reconnect()
}
