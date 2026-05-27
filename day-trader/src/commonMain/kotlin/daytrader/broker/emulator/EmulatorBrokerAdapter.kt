package daytrader.broker.emulator

import daytrader.domain.DeploymentMarket
import daytrader.gateway.BrokerAdapter
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.QuoteSource
import daytrader.marketdata.QuoteUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        onSymbolNeedsLiveQuotes = onSymbolNeedsLiveQuotes
    )
    private var commandLoopJob: Job? = null
    private var marketJob: Job? = null
    private var orderJob: Job? = null
    private var quoteCollectorJob: Job? = null
    private var actorJob: Job? = null
    private val actorChannel = Channel<EmulatorActorMessage>(Channel.UNLIMITED)

    override fun start() {
        emit(GatewayEvent.ConnectionStateChanged(daytrader.gateway.GatewayConnectionState.Disconnected))
        startActorLoop(quoteBus != null)
        commandLoopJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                when (val command = receiveCommand()) {
                    GatewayCommand.Connect -> {
                        engine.handleConnect()
                        engine.finishConnect()
                    }
                    GatewayCommand.Disconnect -> engine.handleDisconnect()
                    GatewayCommand.Reconnect -> engine.handleReconnect()
                    GatewayCommand.Shutdown -> {
                        engine.handleShutdown()
                        return@launch
                    }
                    is GatewayCommand.FetchFirstFifteenMinuteCandle ->
                        launch { engine.fetchFirstFifteenMinuteCandle(command.requestId, command.symbol) }
                    is GatewayCommand.FetchFourteenDayAdr ->
                        launch { engine.fetchFourteenDayAdr(command.requestId, command.symbol) }
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
                    is GatewayCommand.PlaceTouchTurnBracket ->
                        actorChannel.trySend(EmulatorActorMessage.PlaceTouchTurnBracket(command.plan))
                    is GatewayCommand.CancelOpenOrdersForSymbol ->
                        actorChannel.trySend(EmulatorActorMessage.CancelOpenOrders(command.symbol))
                    is GatewayCommand.CloseOpenPositionForSymbol ->
                        actorChannel.trySend(EmulatorActorMessage.ClosePosition(command.symbol))
                    is GatewayCommand.FlattenSymbolForSymbol ->
                        actorChannel.trySend(EmulatorActorMessage.FlattenSymbol(command.symbol))
                    GatewayCommand.RequestExecutions -> engine.republishFills()
                }
            }
        }
        marketJob = scope.launch {
            while (isActive) {
                delay(config.marketTickIntervalMs)
                if (engine.shouldRunMarketTicks()) {
                    actorChannel.send(EmulatorActorMessage.MarketTick)
                }
            }
        }
        orderJob = scope.launch {
            while (isActive) {
                delay(config.orderProgressIntervalMs)
                if (engine.shouldRunOrderSim()) {
                    actorChannel.send(EmulatorActorMessage.OrderProgress)
                }
            }
        }
    }

    private fun startActorLoop(collectFromQuoteBus: Boolean) {
        if (collectFromQuoteBus) {
            val bus = quoteBus ?: error("collectFromQuoteBus requires quoteBus")
            val quoteChannel = bus.subscribeUnlimited(MarketQuoteBus.EMULATOR_SUBSCRIBER_ID)
            quoteCollectorJob = scope.launch {
                for (update in quoteChannel) {
                    if (update.source == QuoteSource.EXTERNAL) {
                        actorChannel.send(EmulatorActorMessage.ExternalQuote(update))
                    }
                }
            }
        }
        actorJob = scope.launch {
            for (message in actorChannel) {
                when (message) {
                    is EmulatorActorMessage.ExternalQuote ->
                        engine.ingestExternalQuote(
                            message.update.symbol,
                            message.update.quote,
                            message.update.priorClose
                        )
                    EmulatorActorMessage.MarketTick -> engine.runMarketTick()
                    EmulatorActorMessage.OrderProgress -> engine.runOrderProgressStep()
                    is EmulatorActorMessage.PlaceTouchTurnBracket ->
                        engine.placeTouchTurnBracket(message.plan)
                    is EmulatorActorMessage.CancelOpenOrders ->
                        engine.cancelOpenOrdersForSymbol(message.symbol)
                    is EmulatorActorMessage.ClosePosition ->
                        engine.closeOpenPositionForSymbol(message.symbol)
                    is EmulatorActorMessage.FlattenSymbol ->
                        engine.flattenSymbolForSymbol(message.symbol)
                }
            }
        }
    }

    /**
     * Publishes a live exchange quote onto [quoteBus] (hybrid mode). When no bus is configured,
     * ingests synchronously on the caller thread (tests).
     */
    fun ingestExternalQuote(symbol: String, quote: LiveQuote, priorClose: Double?) {
        val bus = quoteBus
        if (bus != null) {
            bus.publish(symbol, quote, priorClose, QuoteSource.EXTERNAL)
        } else {
            actorChannel.trySend(
                EmulatorActorMessage.ExternalQuote(
                    QuoteUpdate(
                        symbol = symbol,
                        quote = quote,
                        priorClose = priorClose,
                        source = QuoteSource.EXTERNAL
                    )
                )
            )
        }
    }

    fun ingestLiveQuote(symbol: String, quote: LiveQuote, priorClose: Double?) =
        ingestExternalQuote(symbol, quote, priorClose)

    override fun shutdown() {
        engine.handleShutdown()
        quoteBus?.unsubscribe(MarketQuoteBus.EMULATOR_SUBSCRIBER_ID)
        actorChannel.close()
        commandLoopJob?.cancel()
        marketJob?.cancel()
        orderJob?.cancel()
        quoteCollectorJob?.cancel()
        actorJob?.cancel()
    }

    private sealed interface EmulatorActorMessage {
        data class ExternalQuote(val update: QuoteUpdate) : EmulatorActorMessage
        data object MarketTick : EmulatorActorMessage
        data object OrderProgress : EmulatorActorMessage
        data class PlaceTouchTurnBracket(val plan: daytrader.domain.TouchTurnOrderPlan) : EmulatorActorMessage
        data class CancelOpenOrders(val symbol: String) : EmulatorActorMessage
        data class ClosePosition(val symbol: String) : EmulatorActorMessage
        data class FlattenSymbol(val symbol: String) : EmulatorActorMessage
    }
}
