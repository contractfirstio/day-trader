package daytrader.gateway

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnOrderPlan
import kotlinx.coroutines.flow.StateFlow

interface BrokerGateway {
    val brokerId: BrokerId

    val connectionState: StateFlow<GatewayConnectionState>

    val positions: StateFlow<List<AccountPosition>>

    val openOrders: StateFlow<List<WorkingOrder>>

    val fills: StateFlow<List<BrokerFill>>

    fun connect()

    fun disconnect()

    fun reconnect()

    suspend fun fetchFirstFifteenMinuteCandle(symbol: String): Result<OhlcBar>

    suspend fun fetchFourteenDayAdr(symbol: String): Result<Double>

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan)
}
