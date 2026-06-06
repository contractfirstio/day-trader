package daytrader.e2e.support

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
import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.QuoteSource
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controllable IB market-data gateway for hybrid/IB E2E tests.
 * Does not accept order placement (market-data-only semantics).
 */
class ProgrammableIbMarketDataGateway(
    private val quoteBus: MarketQuoteBus? = null
) : BrokerGateway {
    override val brokerId: BrokerId = BrokerId.INTERACTIVE_BROKERS

    var bootstrapContext: TouchTurnSignalContext = E2ETestFixtures.bootstrapContext()
    var refetchContexts: List<TouchTurnSignalContext> = emptyList()
    val subscribedSymbols = mutableListOf<String>()
    val ensureLiveMarketDataCalls = mutableListOf<String>()

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

    private val _touchTurnBracketPlacements =
        MutableSharedFlow<daytrader.gateway.TouchTurnBracketAck>(extraBufferCapacity = 8)
    override val touchTurnBracketPlacements: SharedFlow<daytrader.gateway.TouchTurnBracketAck> =
        _touchTurnBracketPlacements.asSharedFlow()

    fun resetRefetchIndex() {
        refetchIndex.set(0)
    }

    fun publishQuote(symbol: String, quote: LiveQuote, priorClose: Double? = null) {
        val normalized = quote.copy(symbol = symbol.uppercase())
        _quotes.value = _quotes.value + (normalized.symbol to normalized)
        quoteBus?.publish(normalized.symbol, normalized, priorClose, QuoteSource.EXTERNAL)
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
    ): Result<OhlcBar> = Result.success(bootstrapContext.firstCandle)

    override suspend fun fetchFourteenDayAdr(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<Double> = Result.success(bootstrapContext.atr14)

    override suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity?,
        isClosedBarRefetch: Boolean,
        marketZoneId: String?,
        allowMissingTodayOpeningBar: Boolean,
        rules: daytrader.domain.TouchTurnRuleConfig
    ): Result<TouchTurnSignalContext> {
        if (!isClosedBarRefetch) {
            return Result.success(bootstrapContext)
        }
        if (refetchContexts.isEmpty()) {
            return Result.success(bootstrapContext)
        }
        val index = refetchIndex.getAndIncrement()
        val context = refetchContexts.getOrNull(index) ?: refetchContexts.last()
        return Result.success(context)
    }

    override suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution> =
        Result.success(InstrumentResolution(emptyList()))

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) =
        error("ProgrammableIbMarketDataGateway is market-data-only")

    override fun cancelOrder(orderId: Int) = Unit

    override fun cancelOpenOrdersForSymbol(symbol: String) = Unit

    override fun closeOpenPositionForSymbol(symbol: String) = Unit

    override fun flattenSymbolForSymbol(symbol: String) = Unit

    override fun refreshFills() = Unit

    fun ensureStreaming(symbol: String) {
        ensureLiveMarketDataCalls.add(symbol)
        subscribedSymbols.add(symbol)
        val close = bootstrapContext.firstCandle.close
        publishQuote(
            symbol,
            E2ETestFixtures.liveQuote(
                symbol = symbol,
                bid = close - 0.01,
                ask = close + 0.01,
                last = close
            )
        )
    }
}
