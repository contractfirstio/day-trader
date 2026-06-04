package daytrader.gateway

import daytrader.broker.SymbolMarkets
import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnSignalContext
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

    /**
     * Bootstrap payload for Touch Turn signal engine (opening 15m bar + ATR14 + volume SMA20).
     * Default implementation composes legacy ADR/candle fetches when adapters lack a unified feed.
     */
    suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity? = null,
        isClosedBarRefetch: Boolean = false
    ): Result<TouchTurnSignalContext> {
        val zoneId = SymbolMarkets.marketZoneIdForSession(symbol, instrument)
        val candleResult = fetchFirstFifteenMinuteCandle(symbol, instrument)
        if (candleResult.isFailure) return Result.failure(candleResult.exceptionOrNull()!!)
        val candle = candleResult.getOrThrow()
        val adrResult = fetchFourteenDayAdr(symbol, instrument)
        if (adrResult.isFailure) return Result.failure(adrResult.exceptionOrNull()!!)
        val atrProxy = adrResult.getOrThrow()
        val estimatedVolume = candle.volume.takeIf { it > 0.0 } ?: atrProxy * 10_000.0
        val volumeSma = estimatedVolume * 0.85
        return Result.success(
            TouchTurnSignalContext(
                firstCandle = candle,
                atr14 = atrProxy,
                volumeSma20 = volumeSma
            )
        )
    }

    suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution>

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan)

    /** Cancel a single working order (buffer-zone exhaustion). */
    fun cancelOrder(orderId: Int)

    /** Cancel all open working orders for [symbol] (e.g. when a strategy run stops). */
    fun cancelOpenOrdersForSymbol(symbol: String)

    /** Market-close a non-flat position for [symbol] (e.g. when a strategy run stops). */
    fun closeOpenPositionForSymbol(symbol: String)

    /** Cancel open orders and close any position for [symbol] (session stop). */
    fun flattenSymbolForSymbol(symbol: String)

    /** Ask the broker adapter to reload execution reports into [fills]. */
    fun refreshFills()
}
