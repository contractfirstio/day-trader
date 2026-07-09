package daytrader.gateway

import daytrader.broker.SymbolMarkets
import daytrader.data.ReversalScoreService
import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.HomeMarketMacroBenchmark
import daytrader.domain.MacroRegimeEvaluator
import daytrader.domain.MacroRegimeSnapshot
import daytrader.domain.MacroTrendState
import daytrader.domain.SpyRegimeSnapshot
import daytrader.domain.SpyRegimeEvaluator
import daytrader.domain.StockTrendEvaluator
import daytrader.domain.StockTrendSnapshot
import daytrader.domain.StockTrendState
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnBracketResizeRequest
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

    /** Closed 5m bars with open at or after [afterBarOpenEpochMs] in [marketZoneId]. */
    suspend fun fetchFiveMinuteBars(
        symbol: String,
        instrument: InstrumentIdentity? = null,
        afterBarOpenEpochMs: Long,
        marketZoneId: String
    ): Result<List<OhlcBar>> =
        Result.failure(UnsupportedOperationException("five_minute_bars_not_supported"))

    suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution>

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan)

    /** Modify quantities on an unfilled Touch Turn bracket (paced multi-leg at IB). */
    suspend fun resizeTouchTurnBracket(request: TouchTurnBracketResizeRequest): Result<Unit> =
        Result.failure(UnsupportedOperationException("resize_not_supported"))

    /** Cancel a single working order (buffer-zone exhaustion). */
    fun cancelOrder(orderId: Int)

    /** Cancel all open working orders for [symbol] (e.g. when a strategy run stops). */
    fun cancelOpenOrdersForSymbol(symbol: String, preserveStopLoss: Boolean = false)

    /** Market-close a non-flat position for [symbol] (e.g. when a strategy run stops). */
    fun closeOpenPositionForSymbol(
        symbol: String,
        position: AccountPosition? = null,
        purpose: String = "session_stop"
    )

    /** Cancel open orders and close any position for [symbol] (session stop). */
    fun flattenSymbolForSymbol(symbol: String)

    /** Ask the broker adapter to reload execution reports into [fills]. */
    fun refreshFills()

    /** Ask the broker adapter to reload open positions (no-op when unsupported). */
    fun refreshPositions() = Unit

    /** Latest daily bar close via historical request (no streaming subscription). */
    suspend fun fetchLatestDailyClose(
        symbol: String,
        instrument: InstrumentIdentity? = null
    ): Result<Double>

    /** Daily OHLC bars for watchlist charts (~one month of trading days). */
    suspend fun fetchDailyBars(
        symbol: String,
        instrument: InstrumentIdentity? = null,
        tradingDays: Int = daytrader.presentation.watchlist.WatchlistDailyBars.TRADING_DAYS_ONE_MONTH
    ): Result<List<OhlcBar>> {
        val snapshot = fetchReversalScoreSymbolSnapshot(symbol, instrument).getOrElse { return Result.failure(it) }
        val closes = snapshot.historical.dailyCloses.takeLast(tradingDays)
        if (closes.isEmpty()) {
            return Result.failure(IllegalStateException("No daily bars for $symbol"))
        }
        return Result.success(daytrader.presentation.watchlist.WatchlistDailyBars.fromDailyCloses(closes))
    }

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

    /** Home-market index (SPY / HSI / FTSE) vs 200-SMA for Touch Turn macro alignment. */
    suspend fun fetchHomeMarketRegimeSnapshot(marketZoneId: String): Result<MacroRegimeSnapshot> {
        val benchmark = HomeMarketMacroBenchmark.forMarketZoneId(marketZoneId)
        val close = fetchLatestDailyClose(benchmark.symbol).getOrElse { return Result.failure(it) }
        return Result.success(
            MacroRegimeEvaluator.buildSyntheticSnapshot(
                benchmark = benchmark,
                lastPrice = close,
                trend = MacroTrendState.BULL
            )
        )
    }

    /** Symbol last price and 20-day SMA for stock trend alignment at Touch Turn entry. */
    suspend fun fetchStockTrendSnapshot(
        symbol: String,
        instrument: InstrumentIdentity? = null
    ): Result<StockTrendSnapshot> {
        val snapshot = fetchReversalScoreSymbolSnapshot(symbol, instrument).getOrElse { return Result.failure(it) }
        val lastPrice = snapshot.live.lastPrice
        val observed = snapshot.historical.dailyCloses
        val closes = if (observed.size >= StockTrendEvaluator.SMA_WINDOW) {
            observed
        } else {
            StockTrendEvaluator.paddedDailyCloses(
                lastPrice = lastPrice,
                dailyCloses = observed,
                trend = StockTrendState.UP
            )
        }
        return StockTrendEvaluator.buildSnapshot(lastPrice, closes)
    }
}
