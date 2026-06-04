package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnSignalContext
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Market-data-only [BrokerGateway] that serves captured historical payloads and quotes from a [SessionBundle].
 */
class ReplayMarketDataGateway(
    private val bundle: SessionBundle
) : BrokerGateway {
    private val refetchIndex = AtomicInteger(0)

    private val _connectionState =
        MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    override val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _quotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    override val quotes: StateFlow<Map<String, LiveQuote>> = _quotes.asStateFlow()

    private val _positions = MutableStateFlow<List<AccountPosition>>(emptyList())
    override val positions: StateFlow<List<AccountPosition>> = _positions.asStateFlow()

    private val _openOrders = MutableStateFlow<List<WorkingOrder>>(emptyList())
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()

    private val _fills = MutableStateFlow<List<BrokerFill>>(emptyList())
    override val fills: StateFlow<List<BrokerFill>> = _fills.asStateFlow()

    private val _touchTurnBracketPlacements = MutableSharedFlow<daytrader.gateway.TouchTurnBracketAck>(
        extraBufferCapacity = 8
    )
    override val touchTurnBracketPlacements: SharedFlow<daytrader.gateway.TouchTurnBracketAck> =
        _touchTurnBracketPlacements.asSharedFlow()

    override val brokerId: BrokerId = BrokerId.INTERACTIVE_BROKERS

    fun resetRefetchIndex() {
        refetchIndex.set(0)
    }

    fun updateQuote(event: QuoteEvent) {
        val norm = SymbolMarkets.normalizeSymbol(event.symbol)
        _quotes.value = _quotes.value + (norm to event.quote.copy(symbol = norm))
    }

    override fun connect() {
        _connectionState.value = GatewayConnectionState.Connected
    }

    override fun disconnect() {
        _connectionState.value = GatewayConnectionState.Disconnected
    }

    override fun reconnect() = connect()

    override suspend fun fetchFirstFifteenMinuteCandle(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<OhlcBar> {
        val candle = resolveBootstrapContext()?.firstCandle
            ?: return Result.failure(IllegalStateException("Replay bundle missing bootstrap candle"))
        return Result.success(candle)
    }

    override suspend fun fetchFourteenDayAdr(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<Double> {
        val atr = resolveBootstrapContext()?.atr14
            ?: return Result.failure(IllegalStateException("Replay bundle missing bootstrap ATR"))
        return Result.success(atr)
    }

    override suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity?,
        isClosedBarRefetch: Boolean,
        marketZoneId: String?
    ): Result<TouchTurnSignalContext> {
        if (!isClosedBarRefetch) {
            return resolveBootstrapContext()?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Replay bundle missing bootstrap context"))
        }
        val refetches = bundle.refetchEvents
        if (refetches.isEmpty()) {
            return resolveAcceptedRefetchContext()?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Replay bundle missing refetch context"))
        }
        val index = refetchIndex.getAndIncrement()
        val event = refetches.getOrNull(index) ?: refetches.last()
        return Result.success(event.context)
    }

    override suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution> =
        Result.success(InstrumentResolution(emptyList()))

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) =
        error("ReplayMarketDataGateway is market-data-only")

    override fun cancelOrder(orderId: Int) = Unit

    override fun cancelOpenOrdersForSymbol(symbol: String) = Unit

    override fun closeOpenPositionForSymbol(symbol: String) = Unit

    override fun flattenSymbolForSymbol(symbol: String) = Unit

    override fun refreshFills() = Unit

    private fun resolveBootstrapContext(): TouchTurnSignalContext? =
        bundle.bootstrapContext ?: bundle.groundTruth?.runRecord?.marketInputs?.let { inputs ->
            val bar = inputs.openingBar ?: return null
            val atr = inputs.atr14 ?: inputs.adr14 ?: return null
            val volumeSma = inputs.volumeSma20 ?: return null
            TouchTurnSignalContext(firstCandle = bar, atr14 = atr, volumeSma20 = volumeSma)
        }

    private fun resolveAcceptedRefetchContext(): TouchTurnSignalContext? =
        bundle.acceptedRefetchContext
}
