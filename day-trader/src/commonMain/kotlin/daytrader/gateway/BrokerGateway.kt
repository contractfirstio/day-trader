package daytrader.gateway

import daytrader.domain.OhlcBar
import daytrader.domain.ResolvedInstrument
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

    suspend fun resolveInstrument(symbol: String): Result<ResolvedInstrument>

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan)

    /** Cancel all open working orders for [symbol] (e.g. when a strategy run stops). */
    fun cancelOpenOrdersForSymbol(symbol: String)

    /** Market-close a non-flat position for [symbol] (e.g. when a strategy run stops). */
    fun closeOpenPositionForSymbol(symbol: String)

    /** Cancel open orders and close any position for [symbol] (session stop). */
    fun flattenSymbolForSymbol(symbol: String)

    /** Ask the broker adapter to reload execution reports into [fills]. */
    fun refreshFills()
}
