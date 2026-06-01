package daytrader.gateway

import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.TouchTurnOrderPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface BrokerGateway {
    val brokerId: BrokerId

    val connectionState: StateFlow<GatewayConnectionState>

    val positions: StateFlow<List<AccountPosition>>

    /** Live quotes for symbols that the gateway is subscribed to. */
    val quotes: StateFlow<Map<String, LiveQuote>>

    val openOrders: StateFlow<List<WorkingOrder>>

    val fills: StateFlow<List<BrokerFill>>

    /** Bracket submit acknowledgments from the execution broker (empty when unsupported). */
    val touchTurnBracketPlacements: Flow<TouchTurnBracketAck>
        get() = emptyFlow()

    fun connect()

    fun disconnect()

    fun reconnect()

    suspend fun fetchFirstFifteenMinuteCandle(
        symbol: String,
        instrument: InstrumentIdentity? = null
    ): Result<OhlcBar>

    suspend fun fetchFourteenDayAdr(
        symbol: String,
        instrument: InstrumentIdentity? = null
    ): Result<Double>

    suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution>

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
