package daytrader.engine.support

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
import daytrader.gateway.TouchTurnBracketAck
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeBrokerGateway(
    override val brokerId: BrokerId = BrokerId.EMULATOR,
    adrResult: Result<Double> = Result.success(10.0),
    candleResult: Result<OhlcBar> = Result.success(
        OhlcBar(
            open = 100.0,
            high = 110.0,
            low = 99.0,
            close = 108.0,
            time = "20260522  09:30:00",
            volume = 50_000.0
        )
    ),
    signalContextResult: Result<TouchTurnSignalContext>? = null
) : BrokerGateway {
    var adrFetchResult: Result<Double> = adrResult
    var candleFetchResult: Result<OhlcBar> = candleResult
    var signalContextFetchResult: Result<TouchTurnSignalContext> = signalContextResult
        ?: Result.success(
            TouchTurnSignalContext(
                firstCandle = candleResult.getOrThrow(),
                atr14 = adrResult.getOrThrow(),
                volumeSma20 = 30_000.0
            )
        )
    val placedBrackets = mutableListOf<TouchTurnOrderPlan>()
    val flattenedSymbols = mutableListOf<String>()

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Connected)
    override val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _positions = MutableStateFlow<List<AccountPosition>>(emptyList())
    override val positions: StateFlow<List<AccountPosition>> = _positions.asStateFlow()

    private val _quotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    override val quotes: StateFlow<Map<String, LiveQuote>> = _quotes.asStateFlow()

    private val _openOrders = MutableStateFlow<List<WorkingOrder>>(emptyList())
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()

    private val _fills = MutableStateFlow<List<BrokerFill>>(emptyList())
    override val fills: StateFlow<List<BrokerFill>> = _fills.asStateFlow()

    private val _touchTurnBracketPlacements = MutableSharedFlow<TouchTurnBracketAck>(extraBufferCapacity = 8)
    override val touchTurnBracketPlacements: SharedFlow<TouchTurnBracketAck> =
        _touchTurnBracketPlacements.asSharedFlow()

    fun setPositions(positions: List<AccountPosition>) {
        _positions.value = positions
    }

    fun setOpenOrders(orders: List<WorkingOrder>) {
        _openOrders.value = orders
    }

    fun setFills(fills: List<BrokerFill>) {
        _fills.value = fills
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
    ): Result<OhlcBar> = candleFetchResult

    override suspend fun fetchFourteenDayAdr(
        symbol: String,
        instrument: InstrumentIdentity?
    ): Result<Double> = adrFetchResult

    override suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity?,
        isClosedBarRefetch: Boolean,
        marketZoneId: String?,
        allowMissingTodayOpeningBar: Boolean
    ): Result<TouchTurnSignalContext> = signalContextFetchResult

    override fun cancelOrder(orderId: Int) = Unit

    override suspend fun resolveInstrument(symbol: String): Result<InstrumentResolution> =
        Result.success(InstrumentResolution(emptyList()))

    override fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) {
        placedBrackets.add(plan)
        _touchTurnBracketPlacements.tryEmit(
            TouchTurnBracketAck(
                symbol = plan.symbol,
                orderIds = listOf(1_000, 1_001, 1_002),
                result = Result.success(Unit),
                plan = plan
            )
        )
    }

    override fun cancelOpenOrdersForSymbol(symbol: String) = Unit

    override fun closeOpenPositionForSymbol(symbol: String) = Unit

    override fun flattenSymbolForSymbol(symbol: String) {
        flattenedSymbols.add(symbol)
    }

    override fun refreshFills() = Unit
}
