package daytrader.gateway

import daytrader.broker.SymbolMarkets
import daytrader.data.ReversalScoreService
import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.SpyRegimeSnapshot
import daytrader.domain.SpyRegimeEvaluator
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
        isClosedBarRefetch: Boolean = false,
        marketZoneId: String? = null,
        allowMissingTodayOpeningBar: Boolean = false,
        rules: daytrader.domain.TouchTurnRuleConfig = daytrader.domain.TouchTurnRuleConfig.DEFAULT
    ): Result<TouchTurnSignalContext> {
        val zoneId = SymbolMarkets.marketZoneIdForSession(symbol, instrument, marketZoneId)
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

    /** Latest daily bar close via historical request (no streaming subscription). */
    suspend fun fetchLatestDailyClose(
        symbol: String,
        instrument: InstrumentIdentity? = null
    ): Result<Double>

    /** Live + historical symbol inputs for reversal score (IB reqMktData + reqHistoricalData). */
    suspend fun fetchReversalScoreSymbolSnapshot(
        symbol: String,
        instrument: InstrumentIdentity? = null
    ): Result<ReversalScoreSymbolSnapshot> {
        val close = fetchLatestDailyClose(symbol, instrument).getOrElse { return Result.failure(it) }
        return Result.success(ReversalScoreService.syntheticSymbolSnapshot(close))
    }

    /** VIX / VIX1D / VVIX live and historical inputs for reversal score. */
    suspend fun fetchReversalScoreMacroVolatility(): Result<ReversalScoreMacroVolSnapshot> =
        Result.success(ReversalScoreService.syntheticMacroVolSnapshot())

    /** SPY last price and 200-day SMA for macro trend filter (once per batch). */
    suspend fun fetchSpyRegimeSnapshot(): Result<SpyRegimeSnapshot> {
        val close = fetchLatestDailyClose("SPY").getOrElse { return Result.failure(it) }
        return ReversalScoreService.syntheticSpyRegimeSnapshot(close)
    }
}
