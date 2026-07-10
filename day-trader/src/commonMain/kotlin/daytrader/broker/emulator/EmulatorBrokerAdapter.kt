package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.gateway.BrokerAdapter
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.QuoteSource
import daytrader.marketdata.QuoteUpdate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Single-threaded access to [BrokerEmulatorEngine] via [engineMutex].
 *
 * **Order placement** ([orderActorJob]): brackets, cancels, closes, and coalesced market ticks /
 * order progress — never ingests live quotes.
 *
 * **Pricing** ([pricingActorJob]): coalesced IB quotes from [latestExternalQuotes] only — never
 * shares a channel with bracket placement.
 *
 * Quote bus updates only touch the concurrent quote map; the pricing actor flushes into the engine
 * on its own schedule so a quote flood cannot block [controlChannel].
 */
class EmulatorBrokerAdapter(
    private val emit: (GatewayEvent) -> Unit,
    private val receiveCommand: suspend () -> GatewayCommand,
    private val config: BrokerEmulatorConfig = BrokerEmulatorConfig.Default,
    private val onSymbolNeedsLiveQuotes: (String) -> Unit = {},
    private val quoteBus: MarketQuoteBus? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : BrokerAdapter {

    override val brokerId: BrokerId = BrokerId.EMULATOR

    private val engine = BrokerEmulatorEngine(
        config = config,
        emit = emit,
        onSymbolNeedsLiveQuotes = ::requestLiveQuotesAsync
    )
    private val engineMutex = Mutex()
    private var commandLoopJob: Job? = null
    private var marketJob: Job? = null
    private var orderJob: Job? = null
    private var quoteCollectorJob: Job? = null
    private var orderActorJob: Job? = null
    private var pricingActorJob: Job? = null
    private val controlChannel = Channel<EmulatorControlMessage>(Channel.UNLIMITED)
    private val pendingMarketTick = AtomicBoolean(false)
    private val pendingOrderProgress = AtomicBoolean(false)
    private val latestExternalQuotes = ConcurrentHashMap<String, QuoteUpdate>()

    override fun start() {
        emit(GatewayEvent.ConnectionStateChanged(daytrader.gateway.GatewayConnectionState.Disconnected))
        startQuoteCollector(quoteBus != null)
        startOrderActor()
        startPricingActor()
        commandLoopJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                when (val command = receiveCommand()) {
                    GatewayCommand.Connect -> withEngine {
                        engine.handleConnect()
                        engine.finishConnect()
                    }
                    GatewayCommand.Disconnect -> withEngine { engine.handleDisconnect() }
                    GatewayCommand.Reconnect -> withEngine { engine.handleReconnect() }
                    GatewayCommand.Shutdown -> {
                        withEngine { engine.handleShutdown() }
                        return@launch
                    }
                    GatewayCommand.ResetSessionState -> withEngine { engine.resetSessionState() }
                    is GatewayCommand.PruneSymbolSessionState ->
                        withEngine { engine.pruneSymbolSessionState(command.symbol) }
                    is GatewayCommand.EnsureStreamingMarketData ->
                        withEngine {
                            engine.ensureStreamingMarketData(
                                symbol = command.symbol,
                                instrument = command.instrument,
                                referencePrice = command.referencePrice
                            )
                        }
                    is GatewayCommand.SeedSyntheticQuote ->
                        withEngine {
                            engine.seedSyntheticQuote(
                                symbol = command.symbol,
                                bid = command.bid,
                                ask = command.ask,
                                last = command.last
                            )
                        }
                    is GatewayCommand.FetchFirstFifteenMinuteCandle ->
                        launch {
                            withEngine {
                                engine.fetchFirstFifteenMinuteCandle(command.requestId, command.symbol)
                            }
                        }
                    is GatewayCommand.FetchFourteenDayAdr ->
                        launch {
                            withEngine {
                                engine.fetchFourteenDayAdr(command.requestId, command.symbol)
                            }
                        }
                    is GatewayCommand.FetchLatestDailyClose ->
                        launch {
                            withEngine {
                                engine.fetchLatestDailyClose(
                                    command.requestId,
                                    command.symbol,
                                    command.instrument
                                )
                            }
                        }
                    is GatewayCommand.FetchReversalScoreSymbolSnapshot ->
                        launch {
                            withEngine {
                                engine.fetchReversalScoreSymbolSnapshot(
                                    command.requestId,
                                    command.symbol,
                                    command.instrument
                                )
                            }
                        }
                    is GatewayCommand.FetchReversalScoreMacroVolatility ->
                        launch {
                            withEngine {
                                engine.fetchReversalScoreMacroVolatility(command.requestId)
                            }
                        }
                    is GatewayCommand.FetchSpyRegimeSnapshot ->
                        launch {
                            withEngine {
                                engine.fetchSpyRegimeSnapshot(command.requestId)
                            }
                        }
                    is GatewayCommand.FetchHomeMarketRegimeSnapshot ->
                        launch {
                            withEngine {
                                engine.fetchHomeMarketRegimeSnapshot(
                                    command.requestId,
                                    command.marketZoneId
                                )
                            }
                        }
                    is GatewayCommand.FetchTouchTurnSignalContext ->
                        launch {
                            withEngine {
                                engine.fetchTouchTurnSignalContext(
                                    requestId = command.requestId,
                                    symbol = command.symbol,
                                    isClosedBarRefetch = command.isClosedBarRefetch,
                                    rules = command.rules
                                )
                            }
                        }
                    is GatewayCommand.FetchFiveMinuteBars ->
                        launch {
                            withEngine {
                                engine.fetchFiveMinuteBars(
                                    requestId = command.requestId,
                                    symbol = command.symbol,
                                    afterBarOpenEpochMs = command.afterBarOpenEpochMs,
                                    marketZoneId = command.marketZoneId
                                )
                            }
                        }
                    is GatewayCommand.CancelOrder ->
                        withEngine { engine.cancelOrder(command.orderId) }
                    is GatewayCommand.ResolveInstrument ->
                        emit(
                            GatewayEvent.InstrumentResolved(
                                command.requestId,
                                Result.success(
                                    daytrader.domain.InstrumentResolution(
                                        listOf(DeploymentMarket.fromSymbolHeuristic(command.symbol))
                                    )
                                )
                            )
                        )
                    is GatewayCommand.PlaceTouchTurnBracket -> {
                        EmulatorLog.bracketQueueReceived(
                            command.plan.symbol,
                            latestExternalQuotes.size
                        )
                        controlChannel.send(EmulatorControlMessage.PlaceTouchTurnBracket(command.plan))
                    }
                    is GatewayCommand.ResizeTouchTurnBracket ->
                        scope.launch {
                            val result = withEngine { engine.resizeTouchTurnBracket(command.request) }
                            emit(
                                GatewayEvent.TouchTurnBracketResized(
                                    requestId = command.requestId,
                                    result = result
                                )
                            )
                        }
                    is GatewayCommand.CancelOpenOrdersForSymbol ->
                        controlChannel.send(
                            EmulatorControlMessage.CancelOpenOrders(
                                symbol = command.symbol,
                                preserveStopLoss = command.preserveStopLoss
                            )
                        )
                    is GatewayCommand.CloseOpenPositionForSymbol ->
                        controlChannel.send(
                            EmulatorControlMessage.ClosePosition(
                                symbol = command.symbol,
                                quantity = command.quantity,
                                action = command.action
                            )
                        )
                    is GatewayCommand.TightenOpenDeadlineProtectiveStop ->
                        controlChannel.send(
                            EmulatorControlMessage.TightenOpenDeadlineStop(
                                symbol = command.symbol,
                                position = command.position,
                                newStopPrice = command.newStopPrice
                            )
                        )
                    is GatewayCommand.FlattenSymbolForSymbol ->
                        controlChannel.send(EmulatorControlMessage.FlattenSymbol(command.symbol))
                    GatewayCommand.RequestExecutions -> withEngine { engine.republishFills() }
                    GatewayCommand.RequestPositions -> withEngine { engine.republishPositions() }
                }
            }
        }
        marketJob = scope.launch {
            while (isActive) {
                delay(config.marketTickIntervalMs)
                if (engine.shouldRunMarketTicks()) {
                    pendingMarketTick.set(true)
                }
            }
        }
        orderJob = scope.launch {
            while (isActive) {
                delay(config.orderProgressIntervalMs)
                if (engine.shouldRunOrderSim()) {
                    pendingOrderProgress.set(true)
                }
            }
        }
    }

    private fun startQuoteCollector(collectFromQuoteBus: Boolean) {
        if (!collectFromQuoteBus) return
        val bus = quoteBus ?: error("collectFromQuoteBus requires quoteBus")
        val quoteChannel = bus.subscribeForEmulator()
        quoteCollectorJob = scope.launch {
            for (update in quoteChannel) {
                if (update.source != QuoteSource.EXTERNAL) continue
                if (config.flushEachExternalQuote) {
                    // Replay ingests captured quotes synchronously from [QuoteFeeder]; skip bus coalescing.
                    continue
                }
                latestExternalQuotes[update.symbol] = update
            }
        }
    }

    private fun startOrderActor() {
        orderActorJob = scope.launch {
            while (isActive) {
                drainControlChannel()
                runCoalescedSimulationWork()
                select {
                    controlChannel.onReceive { message ->
                        processControlMessage(message)
                        drainControlChannel()
                        runCoalescedSimulationWork()
                    }
                    onTimeout(ORDER_SELECT_TIMEOUT_MS) { }
                }
            }
        }
    }

    private fun startPricingActor() {
        pricingActorJob = scope.launch {
            while (isActive) {
                flushCoalescedExternalQuotes()
                delay(QUOTE_FLUSH_INTERVAL_MS)
            }
        }
    }

    private suspend fun drainControlChannel() {
        while (true) {
            val message = controlChannel.tryReceive().getOrNull() ?: break
            processControlMessage(message)
        }
    }

    private suspend fun runCoalescedSimulationWork() {
        withEngine {
            if (pendingMarketTick.getAndSet(false) && engine.shouldRunMarketTicks()) {
                engine.runMarketTick()
            }
            if (pendingOrderProgress.getAndSet(false) && engine.shouldRunOrderSim()) {
                engine.runOrderProgressStep()
            }
        }
    }

    private suspend fun flushCoalescedExternalQuotes() {
        if (latestExternalQuotes.isEmpty()) return
        val batch = latestExternalQuotes.values.toList()
        latestExternalQuotes.clear()
        EmulatorLog.quoteFlushBatch(batch.size)
        withEngine {
            for (update in batch) {
                engine.ingestExternalQuote(
                    update.symbol,
                    update.quote,
                    update.priorClose
                )
            }
        }
    }

    private suspend fun processControlMessage(message: EmulatorControlMessage) {
        when (message) {
            is EmulatorControlMessage.PlaceTouchTurnBracket -> {
                val symbol = message.plan.symbol
                EmulatorLog.bracketPlaceStarted(symbol)
                val startedAt = System.currentTimeMillis()
                val durationMs = { System.currentTimeMillis() - startedAt }
                runCatching {
                    withEngine { engine.placeTouchTurnBracket(message.plan) }
                }.fold(
                    onSuccess = {
                        pendingMarketTick.set(true)
                        EmulatorLog.bracketPlaceFinished(
                            symbol = symbol,
                            durationMs = durationMs(),
                            success = true
                        )
                    },
                    onFailure = { error ->
                        EmulatorLog.bracketPlaceFinished(
                            symbol = symbol,
                            durationMs = durationMs(),
                            success = false,
                            errorType = error::class.simpleName,
                            errorMessage = error.message ?: error.toString()
                        )
                    }
                )
            }
            is EmulatorControlMessage.CancelOpenOrders ->
                runCatching {
                    withEngine {
                        engine.cancelOpenOrdersForSymbol(message.symbol, message.preserveStopLoss)
                    }
                }.onFailure { logControlMessageFailure("cancel_open_orders", message.symbol, it) }
            is EmulatorControlMessage.ClosePosition ->
                runCatching {
                    withEngine {
                        engine.closeOpenPositionForSymbol(
                            symbol = message.symbol,
                            quantity = message.quantity,
                            action = message.action
                        )
                    }
                }.onFailure { logControlMessageFailure("close_position", message.symbol, it) }
            is EmulatorControlMessage.TightenOpenDeadlineStop ->
                runCatching {
                    withEngine {
                        engine.tightenOpenDeadlineProtectiveStop(
                            symbol = message.symbol,
                            position = message.position,
                            newStopPrice = message.newStopPrice
                        )
                    }
                }.onFailure { logControlMessageFailure("tighten_open_deadline_stop", message.symbol, it) }
            is EmulatorControlMessage.FlattenSymbol ->
                runCatching {
                    withEngine { engine.flattenSymbolForSymbol(message.symbol) }
                }.onFailure { logControlMessageFailure("flatten_symbol", message.symbol, it) }
        }
    }

    /** IB subscribe must not run on the order actor or inside [engineMutex] — fire-and-forget. */
    private fun requestLiveQuotesAsync(symbol: String) {
        scope.launch {
            runCatching { onSymbolNeedsLiveQuotes(symbol) }
                .onFailure { EmulatorLog.liveQuotesSubscribeFailed(symbol, it) }
        }
    }

    private fun logControlMessageFailure(action: String, symbol: String, error: Throwable) {
        EmulatorLog.controlMessageFailed(action, symbol, error)
    }

    private suspend fun <T> withEngine(block: suspend () -> T): T = engineMutex.withLock { block() }

    /**
     * Publishes a live exchange quote onto [quoteBus] (hybrid mode). When no bus is configured,
     * ingests synchronously on the caller thread (tests).
     */
    fun ingestExternalQuote(symbol: String, quote: LiveQuote, priorClose: Double?) {
        val bus = quoteBus
        if (bus != null) {
            bus.publish(symbol, quote, priorClose, QuoteSource.EXTERNAL)
        } else {
            engine.ingestExternalQuote(symbol, quote, priorClose)
        }
    }

    fun ingestLiveQuote(symbol: String, quote: LiveQuote, priorClose: Double?) =
        ingestExternalQuote(symbol, quote, priorClose)

    /**
     * Evaluates fills immediately on the caller coroutine. Used by headless replay backtest so
     * every captured tick reaches [BrokerEmulatorEngine] without runBlocking or 50ms coalescing.
     */
    suspend fun ingestExternalQuoteFromReplay(symbol: String, quote: LiveQuote, priorClose: Double?) {
        withEngine {
            engine.ingestExternalQuote(symbol, quote, priorClose)
        }
    }

    /**
     * Evaluates fills immediately on the caller thread. Used by interactive replay when the publish
     * path is not suspend-capable.
     */
    fun ingestExternalQuoteSynchronously(symbol: String, quote: LiveQuote, priorClose: Double?) {
        runBlocking {
            withEngine {
                engine.ingestExternalQuote(symbol, quote, priorClose)
            }
        }
    }

    /** Session-scoped synthetic quote streaming (pure emulator mode; mirrors IB hybrid lifecycle). */
    fun ensureStreamingMarketData(symbol: String, instrument: InstrumentIdentity? = null) {
        runBlocking { withEngine { engine.ensureStreamingMarketData(symbol, instrument) } }
    }

    fun ensureStreamingMarketData(
        symbol: String,
        instrument: InstrumentIdentity?,
        referencePrice: Double?
    ) {
        runBlocking {
            withEngine {
                engine.ensureStreamingMarketData(symbol, instrument, referencePrice)
            }
        }
    }

    fun seedSyntheticQuote(symbol: String, bid: Double, ask: Double, last: Double) {
        runBlocking { withEngine { engine.seedSyntheticQuote(symbol, bid, ask, last) } }
    }

    fun releaseStreamingMarketData(symbol: String, instrument: InstrumentIdentity? = null) {
        runBlocking { withEngine { engine.releaseStreamingMarketData(symbol, instrument) } }
    }

    fun resetSessionState() {
        latestExternalQuotes.clear()
        runBlocking { withEngine { engine.resetSessionState() } }
    }

    fun reseedRandom(seed: Long) {
        runBlocking { withEngine { engine.reseedRandom(seed) } }
    }

    fun pruneSymbolSessionState(symbol: String) {
        latestExternalQuotes.remove(SymbolMarkets.normalizeSymbol(symbol))
        runBlocking { withEngine { engine.pruneSymbolSessionState(symbol) } }
    }

    /**
     * Short yield for interactive replay drive loops — enough for the order actor to dequeue one bracket.
     */
    suspend fun yieldOrderActor(maxSpins: Int = 16) {
        repeat(maxSpins) {
            yield()
            delay(ORDER_SELECT_TIMEOUT_MS)
        }
    }

    /**
     * Headless backtest: process queued bracket/control messages without wall-clock pacing.
     */
    suspend fun drainOrderActorQueue(maxRounds: Int = 16) {
        repeat(maxRounds) {
            drainControlChannel()
            runCoalescedSimulationWork()
            yield()
        }
    }

    /**
     * Replay session stop: enqueue flatten on [controlChannel] and drain on the caller thread.
     * [GatewayCommand.FlattenSymbolForSymbol] via [QueuedBrokerGateway] is async (outbound queue →
     * command loop) and can finish after a immediate [drainOrderActorQueue] — use this instead.
     */
    suspend fun flattenSymbolSynchronously(symbol: String, maxRounds: Int = 16) {
        controlChannel.send(EmulatorControlMessage.FlattenSymbol(symbol))
        drainOrderActorQueue(maxRounds = maxRounds)
    }

    /**
     * Longer wait after bracket submit before quote-driven fill replay begins.
     */
    suspend fun awaitIdleForReplay(maxSpins: Int = 512) {
        yieldOrderActor(maxSpins)
    }

    override fun shutdown() {
        commandLoopJob?.cancel()
        marketJob?.cancel()
        orderJob?.cancel()
        quoteCollectorJob?.cancel()
        orderActorJob?.cancel()
        pricingActorJob?.cancel()
        controlChannel.close()
        engine.handleShutdown()
        quoteBus?.unsubscribe(MarketQuoteBus.EMULATOR_SUBSCRIBER_ID)
    }

    private sealed interface EmulatorControlMessage {
        data class PlaceTouchTurnBracket(val plan: daytrader.domain.TouchTurnOrderPlan) : EmulatorControlMessage
        data class CancelOpenOrders(val symbol: String, val preserveStopLoss: Boolean = false) : EmulatorControlMessage
        data class ClosePosition(
            val symbol: String,
            val quantity: Int? = null,
            val action: String? = null
        ) : EmulatorControlMessage
        data class TightenOpenDeadlineStop(
            val symbol: String,
            val position: daytrader.gateway.AccountPosition,
            val newStopPrice: Double
        ) : EmulatorControlMessage
        data class FlattenSymbol(val symbol: String) : EmulatorControlMessage
    }

    companion object {
        private const val QUOTE_FLUSH_INTERVAL_MS = 50L
        private const val ORDER_SELECT_TIMEOUT_MS = 5L
    }
}
