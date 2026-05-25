package daytrader.gateway

import daytrader.domain.OhlcBar
import kotlinx.coroutines.flow.StateFlow

interface BrokerGateway {
    val brokerId: BrokerId

    val connectionState: StateFlow<GatewayConnectionState>

    val positions: StateFlow<List<AccountPosition>>

    val openOrders: StateFlow<List<WorkingOrder>>

    fun connect()

    fun disconnect()

    fun reconnect()

    suspend fun fetchFirstFifteenMinuteCandle(symbol: String): Result<OhlcBar>

    suspend fun fetchFourteenDayAdr(symbol: String): Result<Double>
}
