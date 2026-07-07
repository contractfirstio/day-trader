package daytrader.gateway

import daytrader.broker.SymbolMarkets
import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.HomeMarketMacroBenchmark
import daytrader.domain.MacroRegimeSnapshot
import daytrader.domain.SpyRegimeSnapshot
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnSignalContext
import daytrader.diagnostics.ExecutionGatewayLog
import daytrader.diagnostics.ReversalScoreLog
import daytrader.diagnostics.SessionPriceLog
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
class QueuedBrokerGateway(
    private val sendCommand: (GatewayCommand) -> Unit,
    private val receiveEventBlocking: () -> GatewayEvent,
    override val brokerId: BrokerId,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    initialPauseInboundProcessing: Boolean = false,
) : BrokerGateway {

    private val _connectionState =
        MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    override val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _positions = MutableStateFlow<List<AccountPosition>>(emptyList())
    override val positions: StateFlow<List<AccountPosition>> = _positions.asStateFlow()

    private val _quotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    override val quotes: StateFlow<Map<String, LiveQuote>> = _quotes.asStateFlow()

    private val _openOrders = MutableStateFlow<List<WorkingOrder>>(emptyList())
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()

    private val _fills = MutableStateFlow<List<BrokerFill>>(emptyList())
    override val fills: StateFlow<List<BrokerFill>> = _fills.asStateFlow()

    private val _touchTurnBracketPlacements =
        MutableSharedFlow<TouchTurnBracketAck>(extraBufferCapacity = 32)
    override val touchTurnBracketPlacements: SharedFlow<TouchTurnBracketAck> =
        _touchTurnBracketPlacements.asSharedFlow()

    private var nextRequestId = 1L
    private val requestIdLock = Any()
    private val pendingCandles = mutableMapOf<Long, CompletableDeferred<Result<OhlcBar>>>()
    private val pendingAdr = mutableMapOf<Long, CompletableDeferred<Result<Double>>>()
    private val pendingSignalContext = mutableMapOf<Long, CompletableDeferred<Result<TouchTurnSignalContext>>>()
    private val pendingFiveMinuteBars = mutableMapOf<Long, CompletableDeferred<Result<List<OhlcBar>>>>()
    private val pendingInstrument = mutableMapOf<Long, CompletableDeferred<Result<InstrumentResolution>>>()
    private val pendingLatestDailyClose = mutableMapOf<Long, CompletableDeferred<Result<Double>>>()
    private val pendingReversalScoreSymbol = mutableMapOf<Long, CompletableDeferred<Result<ReversalScoreSymbolSnapshot>>>()
    private val pendingReversalScoreMacro = mutableMapOf<Long, CompletableDeferred<Result<ReversalScoreMacroVolSnapshot>>>()
    private val pendingSpyRegime = mutableMapOf<Long, CompletableDeferred<Result<SpyRegimeSnapshot>>>()
    private val pendingHomeMarketRegime = mutableMapOf<Long, CompletableDeferred<Result<MacroRegimeSnapshot>>>()
    private val pendingBracketResize = mutableMapOf<Long, CompletableDeferred<Result<Unit>>>()

    @Volatile
    private var pauseInboundProcessing = initialPauseInboundProcessing
    private var inboundConsumerJob: Job? = null

    private fun allocateRequestId(): Long = synchronized(requestIdLock) {
        nextRequestId++
    }

    init {
        inboundConsumerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (pauseInboundProcessing) {
                    delay(1L)
                    continue
                }
                val event = withContext(Dispatchers.IO) { receiveEventBlocking() }
                if (event is GatewayEvent.InboundShutdown) break
                apply(event)
            }
        }
    }

    fun setPauseInboundProcessing(paused: Boolean) {
        pauseInboundProcessing = paused
    }

    fun applyInboundEvent(event: GatewayEvent) {
        apply(event)
    }

    fun shutdownInboundConsumer() {
        inboundConsumerJob?.cancel()
        inboundConsumerJob = null
    }

    override fun connect() {
        sendCommand(GatewayCommand.Connect)
    }

    override fun disconnect() {
        sendCommand(GatewayCommand.Disconnect)
    }

    override fun reconnect() {
        sendCommand(GatewayCommand.Reconnect)
    }

    /** Drops cached broker snapshots and cancels in-flight request/response pairs (replay session boundaries). */
    fun resetSessionLiveState() {
        cancelPendingRequests()
        _positions.value = emptyList()
        _quotes.value = emptyMap()
        _openOrders.value = emptyList()
        _fills.value = emptyList()
    }

    /** Clears gateway caches and asks the emulator adapter to drop prior-session trading state. */
    fun requestSessionReset() {
        resetSessionLiveState()
        sendCommand(GatewayCommand.ResetSessionState)
    }

    fun requestEmulatorStreaming(
        symbol: String,
        instrument: InstrumentIdentity? = null,
        referencePrice: Double? = null
    ) {
        if (brokerId != BrokerId.EMULATOR) return
        sendCommand(GatewayCommand.EnsureStreamingMarketData(symbol, instrument, referencePrice))
    }

    fun requestEmulatorSyntheticQuote(
        symbol: String,
        bid: Double,
        ask: Double,
        last: Double
    ) {
        if (brokerId != BrokerId.EMULATOR) return
        sendCommand(GatewayCommand.SeedSyntheticQuote(symbol, bid, ask, last))
    }

    /** Drops cached snapshots and emulator state for [symbol] when parallel sessions continue. */
    fun requestSymbolSessionPrune(symbol: String) {
        pruneSymbolLiveState(symbol)
        sendCommand(GatewayCommand.PruneSymbolSessionState(symbol))
    }

    fun pruneSymbolLiveState(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        _positions.value = _positions.value.filterNot { SymbolMarkets.symbolsMatch(norm, it.symbol) }
        _openOrders.value = _openOrders.value.filterNot { SymbolMarkets.symbolsMatch(norm, it.symbol) }
        _fills.value = _fills.value.filterNot { SymbolMarkets.symbolsMatch(norm, it.symbol) }
        _quotes.value = _quotes.value - norm
    }

    private fun cancelPendingRequests() {
        val cancelled = CancellationException("Gateway session reset")
        pendingCandles.values.forEach { it.cancel(cancelled) }
        pendingCandles.clear()
        pendingAdr.values.forEach { it.cancel(cancelled) }
        pendingAdr.clear()
        pendingSignalContext.values.forEach { it.cancel(cancelled) }
        pendingSignalContext.clear()
        pendingInstrument.values.forEach { it.cancel(cancelled) }
        pendingInstrument.clear()
        pendingLatestDailyClose.values.forEach { it.cancel(cancelled) }
        pendingLatestDailyClose.clear()
        pendingReversalScoreSymbol.values.forEach { it.cancel(cancelled) }
        pendingReversalScoreSymbol.clear()
        pendingReversalScoreMacro.values.forEach { it.cancel(cancelled) }
        pendingReversalScoreMacro.clear()
        pendingSpyRegime.values.forEach { it.cancel(cancelled) }
        pendingSpyRegime.clear()
        pendingHomeMarketRegime.values.forEach { it.cancel(cancelled) }
        pendingHomeMarketRegime.clear()
        pendingBracketResize.values.forEach { it.cancel(cancelled) }
        pendingBracketResize.clear()
    }

    override suspend fun fetchFirstFifteenMinuteCandle(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<OhlcBar> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<OhlcBar>>()
        pendingCandles[requestId] = deferred
        sendCommand(GatewayCommand.FetchFirstFifteenMinuteCandle(requestId, symbol, instrument))
        return try {
            withTimeout(HISTORICAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingCandles.remove(requestId)
            Result.failure(e)
        }
    }

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) {
        sendCommand(GatewayCommand.PlaceTouchTurnBracket(plan))
    }

    override suspend fun resizeTouchTurnBracket(request: TouchTurnBracketResizeRequest): Result<Unit> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingBracketResize[requestId] = deferred
        sendCommand(GatewayCommand.ResizeTouchTurnBracket(requestId, request))
        return try {
            withTimeout(BRACKET_RESIZE_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingBracketResize.remove(requestId)
            Result.failure(e)
        }
    }

    override fun cancelOrder(orderId: Int) {
        sendCommand(GatewayCommand.CancelOrder(orderId))
    }

    override suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity?,
        isClosedBarRefetch: Boolean,
        marketZoneId: String?,
        allowMissingTodayOpeningBar: Boolean,
        rules: daytrader.domain.TouchTurnRuleConfig
    ): Result<TouchTurnSignalContext> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<TouchTurnSignalContext>>()
        pendingSignalContext[requestId] = deferred
        sendCommand(
            GatewayCommand.FetchTouchTurnSignalContext(
                requestId = requestId,
                symbol = symbol,
                instrument = instrument,
                isClosedBarRefetch = isClosedBarRefetch,
                marketZoneId = marketZoneId,
                allowMissingTodayOpeningBar = allowMissingTodayOpeningBar,
                rules = rules
            )
        )
        return try {
            withTimeout(SIGNAL_CONTEXT_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingSignalContext.remove(requestId)
            Result.failure(e)
        }
    }

    override suspend fun fetchFiveMinuteBars(
        symbol: String,
        instrument: InstrumentIdentity?,
        afterBarOpenEpochMs: Long,
        marketZoneId: String
    ): Result<List<OhlcBar>> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<List<OhlcBar>>>()
        pendingFiveMinuteBars[requestId] = deferred
        sendCommand(
            GatewayCommand.FetchFiveMinuteBars(
                requestId = requestId,
                symbol = symbol,
                instrument = instrument,
                afterBarOpenEpochMs = afterBarOpenEpochMs,
                marketZoneId = marketZoneId
            )
        )
        return try {
            withTimeout(HISTORICAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingFiveMinuteBars.remove(requestId)
            Result.failure(e)
        }
    }

    override fun cancelOpenOrdersForSymbol(symbol: String, preserveStopLoss: Boolean) {
        sendCommand(GatewayCommand.CancelOpenOrdersForSymbol(symbol, preserveStopLoss))
    }

    override fun closeOpenPositionForSymbol(symbol: String, position: AccountPosition?) {
        val quantity = position?.quantity?.let { kotlin.math.abs(it) }
        val action = position?.quantity?.let { if (it > 0) "SELL" else "BUY" }
        sendCommand(GatewayCommand.CloseOpenPositionForSymbol(symbol, quantity, action))
    }

    override fun flattenSymbolForSymbol(symbol: String) {
        sendCommand(GatewayCommand.FlattenSymbolForSymbol(symbol))
    }

    override fun refreshFills() {
        sendCommand(GatewayCommand.RequestExecutions)
    }

    override fun refreshPositions() {
        sendCommand(GatewayCommand.RequestPositions)
    }

    override suspend fun fetchFourteenDayAdr(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<Double> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<Double>>()
        pendingAdr[requestId] = deferred
        sendCommand(GatewayCommand.FetchFourteenDayAdr(requestId, symbol, instrument))
        return try {
            withTimeout(HISTORICAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingAdr.remove(requestId)
            Result.failure(e)
        }
    }

    override suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<InstrumentResolution>>()
        pendingInstrument[requestId] = deferred
        sendCommand(GatewayCommand.ResolveInstrument(requestId, symbol))
        return try {
            withTimeout(HISTORICAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingInstrument.remove(requestId)
            Result.failure(e)
        }
    }

    override suspend fun fetchLatestDailyClose(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<Double> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<Double>>()
        pendingLatestDailyClose[requestId] = deferred
        sendCommand(GatewayCommand.FetchLatestDailyClose(requestId, symbol, instrument))
        return try {
            withTimeout(HISTORICAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingLatestDailyClose.remove(requestId)
            Result.failure(e)
        }
    }

    override suspend fun fetchReversalScoreSymbolSnapshot(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<ReversalScoreSymbolSnapshot> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<ReversalScoreSymbolSnapshot>>()
        pendingReversalScoreSymbol[requestId] = deferred
        sendCommand(GatewayCommand.FetchReversalScoreSymbolSnapshot(requestId, symbol, instrument))
        return try {
            withTimeout(REVERSAL_SCORE_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingReversalScoreSymbol.remove(requestId)
            ReversalScoreLog.gatewayRequestFailed("symbol_snapshot", symbol, requestId, e)
            Result.failure(e)
        }
    }

    override suspend fun fetchReversalScoreMacroVolatility(): Result<ReversalScoreMacroVolSnapshot> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<ReversalScoreMacroVolSnapshot>>()
        pendingReversalScoreMacro[requestId] = deferred
        sendCommand(GatewayCommand.FetchReversalScoreMacroVolatility(requestId))
        return try {
            withTimeout(REVERSAL_SCORE_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingReversalScoreMacro.remove(requestId)
            ReversalScoreLog.gatewayRequestFailed("macro_volatility", symbol = null, requestId, e)
            Result.failure(e)
        }
    }

    override suspend fun fetchSpyRegimeSnapshot(): Result<SpyRegimeSnapshot> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<SpyRegimeSnapshot>>()
        pendingSpyRegime[requestId] = deferred
        sendCommand(GatewayCommand.FetchSpyRegimeSnapshot(requestId))
        return try {
            withTimeout(REVERSAL_SCORE_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingSpyRegime.remove(requestId)
            ReversalScoreLog.gatewayRequestFailed("spy_regime", symbol = "SPY", requestId, e)
            Result.failure(e)
        }
    }

    override suspend fun fetchHomeMarketRegimeSnapshot(marketZoneId: String): Result<MacroRegimeSnapshot> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<MacroRegimeSnapshot>>()
        pendingHomeMarketRegime[requestId] = deferred
        sendCommand(GatewayCommand.FetchHomeMarketRegimeSnapshot(requestId, marketZoneId))
        return try {
            withTimeout(REVERSAL_SCORE_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingHomeMarketRegime.remove(requestId)
            ReversalScoreLog.gatewayRequestFailed(
                "home_market_regime",
                symbol = HomeMarketMacroBenchmark.forMarketZoneId(marketZoneId).symbol,
                requestId,
                e
            )
            Result.failure(e)
        }
    }

    private fun apply(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.ConnectionStateChanged -> _connectionState.value = event.state
            is GatewayEvent.PositionsSnapshot -> _positions.value = event.positions
            is GatewayEvent.OpenOrdersSnapshot -> {
                val previous = _openOrders.value.size
                _openOrders.value = event.orders
                ExecutionGatewayLog.openOrdersSnapshot(brokerId, event.orders, previous)
            }
            is GatewayEvent.TouchTurnBracketPlaced -> {
                ExecutionGatewayLog.touchTurnBracketPlaced(brokerId, event.ack)
                _touchTurnBracketPlacements.tryEmit(event.ack)
            }
            is GatewayEvent.FillsSnapshot -> _fills.value = event.fills
            is GatewayEvent.QuotesSnapshot -> {
                SessionPriceLog.recordQuoteSnapshot(brokerId, event.quotes, _quotes.value)
                _quotes.value = event.quotes
            }
            is GatewayEvent.FirstFifteenMinuteCandleReady -> {
                pendingCandles.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.FourteenDayAdrReady -> {
                pendingAdr.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.TouchTurnSignalContextReady -> {
                pendingSignalContext.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.FiveMinuteBarsReady -> {
                pendingFiveMinuteBars.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.InstrumentResolved -> {
                pendingInstrument.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.LatestDailyCloseReady -> {
                pendingLatestDailyClose.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.ReversalScoreSymbolSnapshotReady -> {
                pendingReversalScoreSymbol.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.ReversalScoreMacroVolatilityReady -> {
                pendingReversalScoreMacro.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.SpyRegimeSnapshotReady -> {
                pendingSpyRegime.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.HomeMarketRegimeSnapshotReady -> {
                pendingHomeMarketRegime.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.TouchTurnBracketResized -> {
                pendingBracketResize.remove(event.requestId)?.complete(event.result.map { Unit })
            }
            GatewayEvent.InboundShutdown -> Unit
        }
    }

    companion object {
        const val BRACKET_RESIZE_TIMEOUT_MS = 20_000L
        const val HISTORICAL_REQUEST_TIMEOUT_MS = 30_000L
        /** IB composite aborts first with pending-leg detail; gateway waits slightly longer. */
        const val SIGNAL_CONTEXT_REQUEST_TIMEOUT_MS =
            daytrader.domain.TouchTurnDefaults.SIGNAL_CONTEXT_REQUEST_TIMEOUT_MS + 5_000L
        const val REVERSAL_SCORE_REQUEST_TIMEOUT_MS = 45_000L
    }
}
