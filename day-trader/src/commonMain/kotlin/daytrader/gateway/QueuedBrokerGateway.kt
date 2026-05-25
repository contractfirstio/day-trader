package daytrader.gateway

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnOrderPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
class QueuedBrokerGateway(
    private val sendCommand: (GatewayCommand) -> Unit,
    private val receiveEventBlocking: () -> GatewayEvent,
    override val brokerId: BrokerId,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : BrokerGateway {

    private val _connectionState =
        MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    override val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _positions = MutableStateFlow<List<AccountPosition>>(emptyList())
    override val positions: StateFlow<List<AccountPosition>> = _positions.asStateFlow()

    private val _openOrders = MutableStateFlow<List<WorkingOrder>>(emptyList())
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()

    private val _fills = MutableStateFlow<List<BrokerFill>>(emptyList())
    override val fills: StateFlow<List<BrokerFill>> = _fills.asStateFlow()

    private var nextRequestId = 1L
    private val requestIdLock = Any()
    private val pendingCandles = mutableMapOf<Long, CompletableDeferred<Result<OhlcBar>>>()
    private val pendingAdr = mutableMapOf<Long, CompletableDeferred<Result<Double>>>()

    private fun allocateRequestId(): Long = synchronized(requestIdLock) {
        nextRequestId++
    }

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                val event = withContext(Dispatchers.IO) { receiveEventBlocking() }
                apply(event)
            }
        }
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

    override suspend fun fetchFirstFifteenMinuteCandle(symbol: String): Result<OhlcBar> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<OhlcBar>>()
        pendingCandles[requestId] = deferred
        sendCommand(GatewayCommand.FetchFirstFifteenMinuteCandle(requestId, symbol))
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

    override fun cancelOpenOrdersForSymbol(symbol: String) {
        sendCommand(GatewayCommand.CancelOpenOrdersForSymbol(symbol))
    }

    override fun closeOpenPositionForSymbol(symbol: String) {
        sendCommand(GatewayCommand.CloseOpenPositionForSymbol(symbol))
    }

    override fun flattenSymbolForSymbol(symbol: String) {
        sendCommand(GatewayCommand.FlattenSymbolForSymbol(symbol))
    }

    override fun refreshFills() {
        sendCommand(GatewayCommand.RequestExecutions)
    }

    override suspend fun fetchFourteenDayAdr(symbol: String): Result<Double> {
        val requestId = allocateRequestId()
        val deferred = CompletableDeferred<Result<Double>>()
        pendingAdr[requestId] = deferred
        sendCommand(GatewayCommand.FetchFourteenDayAdr(requestId, symbol))
        return try {
            withTimeout(HISTORICAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingAdr.remove(requestId)
            Result.failure(e)
        }
    }

    private fun apply(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.ConnectionStateChanged -> _connectionState.value = event.state
            is GatewayEvent.PositionsSnapshot -> _positions.value = event.positions
            is GatewayEvent.OpenOrdersSnapshot -> _openOrders.value = event.orders
            is GatewayEvent.FillsSnapshot -> _fills.value = event.fills
            is GatewayEvent.FirstFifteenMinuteCandleReady -> {
                pendingCandles.remove(event.requestId)?.complete(event.result)
            }
            is GatewayEvent.FourteenDayAdrReady -> {
                pendingAdr.remove(event.requestId)?.complete(event.result)
            }
        }
    }

    companion object {
        const val HISTORICAL_REQUEST_TIMEOUT_MS = 30_000L
    }
}
