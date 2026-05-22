package daytrader.broker

import com.ib.client.Bar
import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.Decimal
import com.ib.client.DefaultEWrapper
import com.ib.client.EClientSocket
import com.ib.client.EJavaSignal
import com.ib.client.EReader
import com.ib.client.Order
import com.ib.client.OrderState
import com.ib.client.TickAttrib
import com.ib.client.TickType
import com.ib.client.protobuf.AccountDataEndProto
import com.ib.client.protobuf.HistoricalDataEndProto
import com.ib.client.protobuf.HistoricalDataProto
import com.ib.client.protobuf.PortfolioValueProto
import com.ib.client.protobuf.PositionEndProto
import com.ib.client.protobuf.PositionProto
import com.ib.client.protobuf.TickPriceProto
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * IB Gateway connection aligned with the working ib-sandbox pattern, with paced
 * enrichment requests (contract details + snapshot market data for PnL/names).
 */
class DesktopIbGatewayConnection(
    private val config: IbGatewayConfig = IbGatewayConfig.fromEnvironment(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : IbGatewayConnection, DefaultEWrapper() {

    private val signal = EJavaSignal()
    private val client = EClientSocket(this, signal)
    private val connectMutex = Mutex()
    private val requestPacer = IbRequestPacer(scope)

    private var reader: EReader? = null
    @Volatile
    private var readerActive = false
    private var readerThread: Thread? = null
    @Volatile
    private var enrichmentScheduled = false
    @Volatile
    private var positionsLoadFinished = false

    private val _state = MutableStateFlow<IbConnectionState>(IbConnectionState.Disconnected)
    override val state: StateFlow<IbConnectionState> = _state.asStateFlow()

    private val _positions = MutableStateFlow<List<BrokerPosition>>(emptyList())
    override val positions: StateFlow<List<BrokerPosition>> = _positions.asStateFlow()

    private val _openOrders = MutableStateFlow<List<BrokerOpenOrder>>(emptyList())
    override val openOrders: StateFlow<List<BrokerOpenOrder>> = _openOrders.asStateFlow()

    private val openPositions = ConcurrentHashMap<String, OpenPosition>()
    private val openOrdersById = ConcurrentHashMap<Int, BrokerOpenOrder>()
    @Volatile
    private var openOrdersLoadFinished = false
    private val marketPrices = ConcurrentHashMap<String, Double>()
    private val priorCloses = ConcurrentHashMap<String, Double>()
    private val bidPrices = ConcurrentHashMap<String, Double>()
    private val askPrices = ConcurrentHashMap<String, Double>()

    private val mktDataReqIdToKey = ConcurrentHashMap<Int, String>()
    private val keyToMktDataReqId = ConcurrentHashMap<String, Int>()
    private val contractDetailsReqIdToKey = ConcurrentHashMap<Int, String>()
    private val historicalPrices = ConcurrentHashMap<String, Double>()
    private val historicalReqIdToKey = ConcurrentHashMap<Int, String>()
    private val historicalPendingKeys = ConcurrentHashMap.newKeySet<String>()
    private val historicalLastBarClose = ConcurrentHashMap<Int, Double>()
    private val touchTurnHistoricalBars = ConcurrentHashMap<Int, MutableList<Bar>>()
    private val touchTurnHistoricalSymbol = ConcurrentHashMap<Int, String>()
    private val touchTurnHistoricalDeferred =
        ConcurrentHashMap<Int, CompletableDeferred<Result<OhlcBar>>>()
    private val adrHistoricalBars = ConcurrentHashMap<Int, MutableList<Bar>>()
    private val adrHistoricalSymbol = ConcurrentHashMap<Int, String>()
    private val adrHistoricalDeferred =
        ConcurrentHashMap<Int, CompletableDeferred<Result<Double>>>()
    private val lastTickDiagAtMs = ConcurrentHashMap<String, Long>()
    private val nextMktDataReqId = AtomicInteger(MKT_DATA_REQ_ID_START)
    private val nextContractDetailsReqId = AtomicInteger(CONTRACT_DETAILS_REQ_ID_START)
    private val nextHistoricalReqId = AtomicInteger(HISTORICAL_REQ_ID_START)
    private val nextTouchTurnHistoricalReqId = AtomicInteger(TOUCH_TURN_HISTORICAL_REQ_ID_START)
    private val nextAdrHistoricalReqId = AtomicInteger(ADR_HISTORICAL_REQ_ID_START)

    private var publishDebounceJob: Job? = null
    private var historicalFallbackJob: Job? = null

    override fun connect() {
        scope.launch {
            connectMutex.withLock { performConnect() }
        }
    }

    override fun disconnect() {
        scope.launch {
            connectMutex.withLock { performDisconnect() }
        }
    }

    override fun reconnect() {
        scope.launch {
            connectMutex.withLock {
                performDisconnect()
                delay(IbRequestPacer.RECONNECT_DELAY_MS)
                performConnect()
            }
        }
    }

    override suspend fun fetchFourteenDayAdr(symbol: String): Result<Double> {
        if (!client.isConnected) {
            return Result.failure(IllegalStateException("Not connected to IB Gateway"))
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Symbol is blank"))
        }
        val reqId = nextAdrHistoricalReqId.getAndIncrement()
        val deferred = CompletableDeferred<Result<Double>>()
        adrHistoricalDeferred[reqId] = deferred
        adrHistoricalSymbol[reqId] = trimmed
        val contract = IbContractMapper.stockForHistorical(trimmed)
        requestPacer.enqueue {
            if (!client.isConnected) {
                failAdrHistorical(reqId, "Disconnected before 14-day ADR request")
                return@enqueue
            }
            client.reqHistoricalData(
                reqId,
                contract,
                "",
                ADR_HISTORICAL_DURATION,
                ADR_HISTORICAL_BAR_SIZE,
                HISTORICAL_WHAT_TO_SHOW,
                1,
                1,
                true,
                null
            )
        }
        return try {
            withTimeout(ADR_HISTORICAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            cancelAdrHistorical(reqId)
            Result.failure(e)
        }
    }

    override suspend fun fetch(symbol: String): Result<OhlcBar> {
        if (!client.isConnected) {
            return Result.failure(IllegalStateException("Not connected to IB Gateway"))
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Symbol is blank"))
        }
        val reqId = nextTouchTurnHistoricalReqId.getAndIncrement()
        val deferred = CompletableDeferred<Result<OhlcBar>>()
        touchTurnHistoricalDeferred[reqId] = deferred
        touchTurnHistoricalSymbol[reqId] = trimmed
        val contract = IbContractMapper.stockForHistorical(trimmed)
        requestPacer.enqueue {
            if (!client.isConnected) {
                failTouchTurnHistorical(
                    reqId,
                    "Disconnected before first 15-minute candle request"
                )
                return@enqueue
            }
            client.reqHistoricalData(
                reqId,
                contract,
                "",
                TOUCH_TURN_HISTORICAL_DURATION,
                TOUCH_TURN_HISTORICAL_BAR_SIZE,
                HISTORICAL_WHAT_TO_SHOW,
                1,
                1,
                true,
                null
            )
        }
        return try {
            withTimeout(TOUCH_TURN_HISTORICAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            cancelTouchTurnHistorical(reqId)
            Result.failure(e)
        }
    }

    fun shutdown() {
        runBlocking {
            connectMutex.withLock { performDisconnect() }
        }
    }

    override fun nextValidId(orderId: Int) {
        _state.value = IbConnectionState.Connected(nextOrderId = orderId)
        IbGatewayLog.nextValidId(orderId)
        // Delayed-frozen: last delayed quote when the exchange is closed (US/UK overnight).
        client.reqMarketDataType(MARKET_DATA_TYPE_DELAYED_FROZEN)
        client.reqAccountUpdates(true, config.accountCode)
        requestPositions()
        requestOpenOrders()
    }

    override fun openOrder(orderId: Int, contract: Contract, order: Order, orderState: OrderState) {
        try {
            applyOpenOrder(orderId, contract, order, orderState)
        } catch (e: Exception) {
            logCallbackFailure("openOrder", e)
        }
    }

    override fun openOrderEnd() {
        try {
            finishOpenOrdersLoad()
        } catch (e: Exception) {
            logCallbackFailure("openOrderEnd", e)
        }
    }

    override fun orderStatus(
        orderId: Int,
        status: String,
        filled: Decimal,
        remaining: Decimal,
        avgFillPrice: Double,
        permId: Long,
        parentId: Int,
        lastFillPrice: Double,
        clientId: Int,
        whyHeld: String,
        mktCapPrice: Double
    ) {
        try {
            applyOrderStatus(orderId, status, filled, remaining)
        } catch (e: Exception) {
            logCallbackFailure("orderStatus", e)
        }
    }

    override fun connectionClosed() {
        stopReader()
        clearPositionState()
        if (_state.value !is IbConnectionState.Disconnected) {
            _state.value = IbConnectionState.Disconnected
        }
        IbGatewayLog.connectionClosed()
    }

    override fun position(account: String, contract: Contract, pos: Decimal, avgCost: Double) {
        try {
            applyPosition(account, contract, pos, avgCost)
        } catch (e: Exception) {
            logCallbackFailure("position", e)
        }
    }

    override fun positionProtoBuf(position: PositionProto.Position) {
        try {
            if (!position.hasContract()) return
            val account = if (position.hasAccount()) position.account else ""
            val contract = IbContractMapper.fromProto(position.contract)
            val quantity = if (position.hasPosition()) Decimal.parse(position.position) else Decimal.ZERO
            val avgCost = if (position.hasAvgCost()) position.avgCost else 0.0
            applyPosition(account, contract, quantity, avgCost)
        } catch (e: Exception) {
            logCallbackFailure("positionProtoBuf", e)
        }
    }

    override fun positionEnd() {
        finishPositionsLoad()
    }

    override fun positionEndProtoBuf(positionEnd: PositionEndProto.PositionEnd) {
        finishPositionsLoad()
    }

    override fun tickPrice(tickerId: Int, field: Int, price: Double, attribs: TickAttrib?) {
        try {
            applyTickPrice(tickerId, field, price)
        } catch (e: Exception) {
            logCallbackFailure("tickPrice", e)
        }
    }

    override fun tickPriceProtoBuf(tickPrice: TickPriceProto.TickPrice) {
        try {
            if (!tickPrice.hasReqId() || !tickPrice.hasTickType() || !tickPrice.hasPrice()) return
            applyTickPrice(tickPrice.reqId, tickPrice.tickType, tickPrice.price)
        } catch (e: Exception) {
            logCallbackFailure("tickPriceProtoBuf", e)
        }
    }

    override fun tickSnapshotEnd(tickerId: Int) {
        IbGatewayLog.marketDataSnapshotComplete(tickerId)
    }

    override fun contractDetails(reqId: Int, contractDetails: ContractDetails) {
        try {
            applyContractDetails(reqId, contractDetails)
        } catch (e: Exception) {
            logCallbackFailure("contractDetails", e)
        }
    }

    override fun updatePortfolio(
        contract: Contract,
        position: Decimal,
        marketPrice: Double,
        marketValue: Double,
        averageCost: Double,
        unrealizedPNL: Double,
        realizedPNL: Double,
        accountName: String
    ) {
        try {
            applyPortfolioUpdate(
                account = accountName,
                contract = contract,
                marketPrice = marketPrice,
                averageCost = averageCost,
                unrealizedPNL = unrealizedPNL
            )
        } catch (e: Exception) {
            logCallbackFailure("updatePortfolio", e)
        }
    }

    override fun accountDownloadEnd(accountName: String) {
        IbGatewayLog.accountDownloadEnd()
    }

    override fun accountDataEndProtoBuf(accountDataEnd: AccountDataEndProto.AccountDataEnd) {
        IbGatewayLog.accountDownloadEnd()
    }

    override fun historicalData(reqId: Int, bar: Bar) {
        try {
            if (adrHistoricalDeferred.containsKey(reqId)) {
                if (bar.high() > 0.0 && bar.low() > 0.0) {
                    adrHistoricalBars.getOrPut(reqId) { mutableListOf() }.add(bar)
                }
                return
            }
            if (touchTurnHistoricalDeferred.containsKey(reqId)) {
                if (bar.high() > 0.0 && bar.low() > 0.0) {
                    touchTurnHistoricalBars.getOrPut(reqId) { mutableListOf() }.add(bar)
                }
                return
            }
            if (bar.close() > 0.0) {
                historicalLastBarClose[reqId] = bar.close()
            }
        } catch (e: Exception) {
            logCallbackFailure("historicalData", e)
        }
    }

    override fun historicalDataEnd(reqId: Int, start: String?, end: String?) {
        try {
            if (adrHistoricalDeferred.containsKey(reqId)) {
                completeAdrHistorical(reqId)
                return
            }
            if (touchTurnHistoricalDeferred.containsKey(reqId)) {
                completeTouchTurnHistorical(reqId)
                return
            }
            applyHistoricalClose(reqId)
        } catch (e: Exception) {
            logCallbackFailure("historicalDataEnd", e)
        }
    }

    override fun historicalDataProtoBuf(historicalData: HistoricalDataProto.HistoricalData) {
        try {
            if (!historicalData.hasReqId()) return
            val reqId = historicalData.reqId
            for (i in historicalData.historicalDataBarsCount - 1 downTo 0) {
                val bar = historicalData.getHistoricalDataBars(i)
                if (bar.hasClose() && bar.close > 0.0) {
                    historicalLastBarClose[reqId] = bar.close
                    break
                }
            }
        } catch (e: Exception) {
            logCallbackFailure("historicalDataProtoBuf", e)
        }
    }

    override fun historicalDataEndProtoBuf(historicalDataEnd: HistoricalDataEndProto.HistoricalDataEnd) {
        try {
            if (historicalDataEnd.hasReqId()) {
                applyHistoricalClose(historicalDataEnd.reqId)
            }
        } catch (e: Exception) {
            logCallbackFailure("historicalDataEndProtoBuf", e)
        }
    }

    override fun updatePortfolioProtoBuf(portfolioValue: PortfolioValueProto.PortfolioValue) {
        try {
            if (!portfolioValue.hasContract()) return
            val account = if (portfolioValue.hasAccountName()) portfolioValue.accountName else ""
            val contract = IbContractMapper.fromProto(portfolioValue.contract)
            val marketPrice = if (portfolioValue.hasMarketPrice()) portfolioValue.marketPrice else 0.0
            val averageCost = if (portfolioValue.hasAverageCost()) portfolioValue.averageCost else 0.0
            val unrealized = if (portfolioValue.hasUnrealizedPNL()) portfolioValue.unrealizedPNL else 0.0
            applyPortfolioUpdate(account, contract, marketPrice, averageCost, unrealized)
        } catch (e: Exception) {
            logCallbackFailure("updatePortfolioProtoBuf", e)
        }
    }

    override fun error(
        reqId: Int,
        errorTime: Long,
        errorCode: Int,
        errorMsg: String,
        advancedOrderRejectJson: String?
    ) {
        when {
            errorCode in INFO_ERROR_CODES -> return
            else -> IbGatewayLog.apiError(reqId, errorCode, errorMsg)
        }

        historicalReqIdToKey[reqId]?.let { key ->
            historicalReqIdToKey.remove(reqId)
            historicalLastBarClose.remove(reqId)
            historicalPendingKeys.remove(key)
        }

        failTouchTurnHistorical(reqId, errorMsg)
        failAdrHistorical(reqId, errorMsg)

        when {
            errorCode == 100 -> _state.value = IbConnectionState.Error(
                "IB API rate limit exceeded (50 messages/sec). Slowing down requests."
            )
            errorCode == 101 -> _state.value = IbConnectionState.Error(
                "IB market data line limit reached. Reduce active subscriptions or close other clients."
            )
            errorCode == 502 -> _state.value = IbConnectionState.Error(
                "Cannot connect to IB Gateway at ${config.endpoint}. Is Gateway running and API enabled?"
            )
            errorCode in FATAL_ERROR_CODES -> _state.value = IbConnectionState.Error("[$errorCode] $errorMsg")
            _state.value is IbConnectionState.Connecting && reqId == -1 -> {
                _state.value = IbConnectionState.Error("[$errorCode] $errorMsg")
            }
        }
    }

    private fun applyPosition(account: String, contract: Contract, pos: Decimal, avgCost: Double) {
        val safeAccount = account.orEmpty()
        val symbol = resolveSymbol(contract)
        val key = positionKey(safeAccount, contract, symbol)
        val quantity = pos.value().toDouble().roundToInt()

        if (!Decimal.isValid(pos) || quantity == 0) {
            if (openPositions.containsKey(key)) {
                IbGatewayLog.positionRemoved(contract)
            }
            openPositions.remove(key)
            cancelMarketData(key)
            return
        }

        val existing = openPositions[key]
        if (existing != null &&
            existing.quantity == quantity &&
            existing.avgCostRaw == avgCost
        ) {
            return
        }

        val magnifier = IbPriceScale.defaultMagnifier(contract)
        val companyName = resolveCompanyName(contract, symbol)

        openPositions[key] = OpenPosition(
            key = key,
            account = safeAccount,
            contract = IbContractMapper.clone(contract),
            symbol = symbol,
            companyName = companyName,
            quantity = quantity,
            avgCostRaw = avgCost,
            priceMagnifier = magnifier,
            needsContractDetails = needsContractDetails(companyName, symbol)
        )
        IbGatewayLog.positionApplied(contract, quantity, avgCost, magnifier)
        logPositionDiag(openPositions[key]!!, "position")
        publishPositions(immediate = true)
        if (enrichmentScheduled) {
            scheduleEnrichmentFor(openPositions[key]!!)
        }
    }

    private fun finishPositionsLoad() {
        publishPositions(immediate = true)
        if (positionsLoadFinished) return
        positionsLoadFinished = true
        enrichmentScheduled = true
        schedulePositionEnrichment()
        IbGatewayLog.positionsLoadComplete(openPositions.size)
        if (IbGatewayLog.isPositionDiagEnabled()) {
            openPositions.values.forEach { logPositionDiag(it, "positions_load_complete") }
        }
        scheduleHistoricalFallbackPass()
    }

    private fun schedulePositionEnrichment() {
        openPositions.values.forEach { open ->
            scheduleEnrichmentFor(open)
        }
    }

    private fun scheduleEnrichmentFor(open: OpenPosition) {
        val key = open.key
        if (open.needsContractDetails && !contractDetailsReqIdToKey.containsValue(key)) {
            requestPacer.enqueue {
                enqueueContractDetails(key, open)
            }
        }
        if (!keyToMktDataReqId.containsKey(key)) {
            requestPacer.enqueue {
                enqueueStreamingMarketData(key, open)
            }
        }
    }

    private fun enqueueContractDetails(key: String, open: OpenPosition) {
        if (!client.isConnected || contractDetailsReqIdToKey.containsValue(key)) return
        val reqId = nextContractDetailsReqId.getAndIncrement()
        contractDetailsReqIdToKey[reqId] = key
        client.reqContractDetails(reqId, IbContractMapper.forDataRequest(open.contract))
    }

    private fun enqueueStreamingMarketData(key: String, open: OpenPosition) {
        if (!client.isConnected || keyToMktDataReqId.containsKey(key)) return
        val reqId = nextMktDataReqId.getAndIncrement()
        mktDataReqIdToKey[reqId] = key
        keyToMktDataReqId[key] = reqId
        client.reqMktData(reqId, IbContractMapper.forDataRequest(open.contract), "", false, false, null)
    }

    private fun applyTickPrice(tickerId: Int, field: Int, price: Double) {
        if (price <= 0.0) return
        val key = mktDataReqIdToKey[tickerId] ?: return
        var priceUpdated = false

        when (field) {
            TickType.LAST.index(),
            TickType.DELAYED_LAST.index() -> {
                marketPrices[key] = price
                priceUpdated = true
            }
            TickType.CLOSE.index(),
            TickType.DELAYED_CLOSE.index() -> {
                priorCloses[key] = price
                if (marketPrices[key] == null) {
                    marketPrices[key] = price
                    priceUpdated = true
                }
            }
            TickType.BID.index(),
            TickType.DELAYED_BID.index() -> {
                bidPrices[key] = price
                priceUpdated = updateMidPrice(key) || priceUpdated
            }
            TickType.ASK.index(),
            TickType.DELAYED_ASK.index() -> {
                askPrices[key] = price
                priceUpdated = updateMidPrice(key) || priceUpdated
            }
        }

        if (priceUpdated) {
            IbGatewayLog.tickPrice(key, field, price)
            openPositions[key]?.let { logPositionDiagThrottled(it, "tick") }
            publishPositions(immediate = false)
        }
    }

    private fun updateMidPrice(key: String): Boolean {
        val bid = bidPrices[key]
        val ask = askPrices[key]
        if (bid != null && ask != null && bid > 0 && ask > 0) {
            marketPrices[key] = (bid + ask) / 2.0
            return true
        }
        return false
    }

    private suspend fun performConnect() {
        if (_state.value is IbConnectionState.Connecting || _state.value is IbConnectionState.Connected) {
            return
        }

        enrichmentScheduled = false
        positionsLoadFinished = false
        _state.value = IbConnectionState.Connecting
        IbGatewayLog.connecting(config.endpoint, config.clientId)

        try {
            client.eConnect(config.host, config.port, config.clientId)
            if (!client.isConnected) {
                _state.value = IbConnectionState.Error(
                    "eConnect returned but socket is not connected (${config.endpoint})"
                )
                return
            }

            IbGatewayLog.connected(config.endpoint, config.clientId)
            startReader()
        } catch (e: Exception) {
            _state.value = IbConnectionState.Error(e.message ?: "Connect failed")
            logCallbackFailure("performConnect", e)
        }
    }

    private fun performDisconnect() {
        stopReader()
        requestPacer.clear()
        if (client.isConnected) {
            client.reqAccountUpdates(false, config.accountCode)
            cancelAllMarketDataImmediate()
            cancelAllContractDetailsImmediate()
            cancelAllHistoricalImmediate()
            cancelAllTouchTurnHistoricalImmediate()
            cancelAllAdrHistoricalImmediate()
            client.eDisconnect()
        }
        clearPositionState()
        _state.value = IbConnectionState.Disconnected
        IbGatewayLog.disconnected()
    }

    private fun requestPositions() {
        if (!client.isConnected) return
        enrichmentScheduled = false
        positionsLoadFinished = false
        requestPacer.clear()
        historicalFallbackJob?.cancel()
        historicalFallbackJob = null
        cancelAllMarketDataImmediate()
        cancelAllContractDetailsImmediate()
        cancelAllHistoricalImmediate()
        openPositions.clear()
        marketPrices.clear()
        historicalPrices.clear()
        priorCloses.clear()
        bidPrices.clear()
        askPrices.clear()
        _positions.value = emptyList()
        IbGatewayLog.requestingPositions()
        client.reqPositions()
    }

    private fun cancelMarketData(key: String) {
        val reqId = keyToMktDataReqId.remove(key) ?: return
        mktDataReqIdToKey.remove(reqId)
        requestPacer.enqueue {
            if (client.isConnected) {
                client.cancelMktData(reqId)
            }
        }
        marketPrices.remove(key)
        priorCloses.remove(key)
        bidPrices.remove(key)
        askPrices.remove(key)
    }

    private fun cancelAllMarketDataImmediate() {
        keyToMktDataReqId.entries.toList().forEach { (key, reqId) ->
            keyToMktDataReqId.remove(key)
            mktDataReqIdToKey.remove(reqId)
            if (client.isConnected) {
                client.cancelMktData(reqId)
            }
        }
        marketPrices.clear()
        priorCloses.clear()
        bidPrices.clear()
        askPrices.clear()
    }

    private fun cancelAllContractDetailsImmediate() {
        contractDetailsReqIdToKey.keys.toList().forEach { reqId ->
            contractDetailsReqIdToKey.remove(reqId)
            if (client.isConnected) {
                client.cancelContractData(reqId)
            }
        }
        contractDetailsReqIdToKey.clear()
    }

    private fun cancelAllHistoricalImmediate() {
        historicalFallbackJob?.cancel()
        historicalFallbackJob = null
        historicalReqIdToKey.keys.toList().forEach { reqId ->
            historicalReqIdToKey.remove(reqId)
            historicalLastBarClose.remove(reqId)
            if (client.isConnected) {
                client.cancelHistoricalData(reqId)
            }
        }
        historicalPrices.clear()
        historicalPendingKeys.clear()
        historicalLastBarClose.clear()
        lastTickDiagAtMs.clear()
    }

    private fun cancelAllTouchTurnHistoricalImmediate() {
        touchTurnHistoricalDeferred.keys.toList().forEach { reqId ->
            cancelTouchTurnHistorical(reqId)
        }
        touchTurnHistoricalBars.clear()
        touchTurnHistoricalSymbol.clear()
        touchTurnHistoricalDeferred.clear()
        cancelAllAdrHistoricalImmediate()
    }

    private fun cancelAllAdrHistoricalImmediate() {
        adrHistoricalDeferred.keys.toList().forEach { reqId ->
            cancelAdrHistorical(reqId)
        }
        adrHistoricalBars.clear()
        adrHistoricalSymbol.clear()
        adrHistoricalDeferred.clear()
    }

    private fun cancelAdrHistorical(reqId: Int) {
        adrHistoricalBars.remove(reqId)
        adrHistoricalSymbol.remove(reqId)
        adrHistoricalDeferred.remove(reqId)?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(Result.failure(IllegalStateException("ADR request cancelled")))
            }
        }
        if (client.isConnected) {
            runCatching { client.cancelHistoricalData(reqId) }
        }
    }

    private fun failAdrHistorical(reqId: Int, message: String) {
        adrHistoricalBars.remove(reqId)
        adrHistoricalSymbol.remove(reqId)
        adrHistoricalDeferred.remove(reqId)?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(Result.failure(IllegalStateException(message)))
            }
        }
    }

    private fun completeAdrHistorical(reqId: Int) {
        val deferred = adrHistoricalDeferred.remove(reqId) ?: return
        val symbol = adrHistoricalSymbol.remove(reqId)
        val bars = adrHistoricalBars.remove(reqId).orEmpty().map { it.toTouchTurnOhlcBar() }
        val sessionDay = sessionDayYyyyMmDd(symbol)
        val adrResult = TouchTurnLogic.computeAdr14(bars, excludeSessionDayYyyyMmdd = sessionDay)
        deferred.complete(adrResult)
    }

    private fun cancelTouchTurnHistorical(reqId: Int) {
        touchTurnHistoricalBars.remove(reqId)
        touchTurnHistoricalSymbol.remove(reqId)
        touchTurnHistoricalDeferred.remove(reqId)?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(Result.failure(IllegalStateException("Historical request cancelled")))
            }
        }
        if (client.isConnected) {
            runCatching { client.cancelHistoricalData(reqId) }
        }
    }

    private fun failTouchTurnHistorical(reqId: Int, message: String) {
        touchTurnHistoricalBars.remove(reqId)
        touchTurnHistoricalSymbol.remove(reqId)
        touchTurnHistoricalDeferred.remove(reqId)?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(Result.failure(IllegalStateException(message)))
            }
        }
    }

    private fun completeTouchTurnHistorical(reqId: Int) {
        val deferred = touchTurnHistoricalDeferred.remove(reqId) ?: return
        val symbol = touchTurnHistoricalSymbol.remove(reqId)
        val bars = touchTurnHistoricalBars.remove(reqId).orEmpty()
        val sessionDay = sessionDayYyyyMmDd(symbol)
        val sessionBars = bars
            .filter { it.high() > 0.0 && it.low() > 0.0 }
            .filter { bar -> bar.time()?.trim()?.startsWith(sessionDay) == true }
        val candidates = sessionBars.ifEmpty {
            bars.filter { it.high() > 0.0 && it.low() > 0.0 }
        }
        val first = candidates.minByOrNull { barTimeSortKey(it.time()) }
        if (first == null) {
            val market = if (symbol != null && SymbolMarkets.isHongKong(symbol)) "SEHK" else "US"
            deferred.complete(
                Result.failure(
                    IllegalStateException("No 15-minute bars returned for $market session ($sessionDay)")
                )
            )
            return
        }
        deferred.complete(Result.success(first.toTouchTurnOhlcBar()))
    }

    private fun clearPositionState() {
        cancelAllMarketDataImmediate()
        cancelAllContractDetailsImmediate()
        cancelAllHistoricalImmediate()
        cancelAllTouchTurnHistoricalImmediate()
        cancelAllAdrHistoricalImmediate()
        openPositions.clear()
        _positions.value = emptyList()
        openOrdersById.clear()
        _openOrders.value = emptyList()
        openOrdersLoadFinished = false
        enrichmentScheduled = false
        positionsLoadFinished = false
        publishDebounceJob?.cancel()
        publishDebounceJob = null
    }

    private fun requestOpenOrders() {
        if (!client.isConnected) return
        openOrdersLoadFinished = false
        openOrdersById.clear()
        _openOrders.value = emptyList()
        IbGatewayLog.requestingOpenOrders()
        client.reqAllOpenOrders()
    }

    private fun applyOpenOrder(orderId: Int, contract: Contract, order: Order, orderState: OrderState) {
        val status = orderStatusLabel(orderState)
        if (isTerminalOrderStatus(status)) {
            openOrdersById.remove(orderId)
            return
        }
        val remaining = remainingQuantity(order)
        if (remaining <= 0) {
            openOrdersById.remove(orderId)
            return
        }
        openOrdersById[orderId] = toBrokerOpenOrder(orderId, contract, order, orderState)
        publishOpenOrders()
    }

    private fun applyOrderStatus(orderId: Int, status: String, filled: Decimal, remaining: Decimal) {
        if (isTerminalOrderStatus(status)) {
            openOrdersById.remove(orderId)
            publishOpenOrders()
            return
        }
        val existing = openOrdersById[orderId] ?: return
        val remainingQty = decimalToInt(remaining)
        if (remainingQty <= 0) {
            openOrdersById.remove(orderId)
            publishOpenOrders()
            return
        }
        openOrdersById[orderId] = existing.copy(
            status = status,
            filled = decimalToInt(filled),
            remaining = remainingQty
        )
        publishOpenOrders()
    }

    private fun finishOpenOrdersLoad() {
        if (openOrdersLoadFinished) return
        openOrdersLoadFinished = true
        publishOpenOrders()
        IbGatewayLog.openOrdersLoadComplete(openOrdersById.size)
    }

    private fun publishOpenOrders() {
        _openOrders.value = openOrdersById.values.sortedBy { it.orderId }
    }

    private fun toBrokerOpenOrder(
        orderId: Int,
        contract: Contract,
        order: Order,
        orderState: OrderState
    ): BrokerOpenOrder {
        val total = decimalToInt(order.totalQuantity())
        val filled = decimalToInt(order.filledQuantity())
        val limit = order.lmtPrice().takeIf { it > 0 }
        val stop = order.auxPrice().takeIf { it > 0 }
        return BrokerOpenOrder(
            orderId = orderId,
            symbol = resolveSymbol(contract),
            action = order.getAction().orEmpty(),
            quantity = total,
            filled = filled,
            remaining = (total - filled).coerceAtLeast(0),
            orderType = order.getOrderType().orEmpty(),
            limitPrice = limit,
            stopPrice = stop,
            status = orderStatusLabel(orderState),
            currency = contract.currency().orEmpty().ifBlank { "USD" }
        )
    }

    private fun orderStatusLabel(orderState: OrderState): String =
        orderState.getStatus().ifBlank { orderState.status().name }

    private fun remainingQuantity(order: Order): Int {
        val total = decimalToInt(order.totalQuantity())
        val filled = decimalToInt(order.filledQuantity())
        return (total - filled).coerceAtLeast(0)
    }

    private fun decimalToInt(value: Decimal): Int =
        if (!Decimal.isValid(value)) 0 else value.value().toDouble().roundToInt()

    private fun isTerminalOrderStatus(status: String): Boolean =
        status.equals("Filled", ignoreCase = true) ||
            status.equals("Cancelled", ignoreCase = true) ||
            status.equals("ApiCancelled", ignoreCase = true) ||
            status.equals("Inactive", ignoreCase = true)

    /**
     * @param immediate true for position load/change — publish now.
     *                  false for price ticks — debounce so rapid ticks coalesce.
     */
    private fun publishPositions(immediate: Boolean = false) {
        if (immediate) {
            publishDebounceJob?.cancel()
            publishDebounceJob = null
            doPublishPositions()
            return
        }
        publishDebounceJob?.cancel()
        publishDebounceJob = scope.launch {
            delay(PUBLISH_THROTTLE_MS)
            doPublishPositions()
        }
    }

    private fun applyContractDetails(reqId: Int, contractDetails: ContractDetails) {
        val key = contractDetailsReqIdToKey[reqId] ?: return
        val open = openPositions[key] ?: return
        val magnifier = IbPriceScale.resolveMagnifier(contractDetails.priceMagnifier(), open.contract)
        val longName = contractDetails.longName()
        val marketName = contractDetails.marketName()
        val companyName = when {
            !longName.isNullOrBlank() -> longName
            !marketName.isNullOrBlank() -> marketName
            else -> open.companyName
        }
        val ibMagnifier = contractDetails.priceMagnifier()
        openPositions[key] = open.copy(
            companyName = companyName,
            priceMagnifier = magnifier,
            contractDetailsMagnifier = ibMagnifier
        )
        IbGatewayLog.contractDetailsApplied(reqId, open.contract, ibMagnifier, magnifier)
        logPositionDiag(openPositions[key]!!, "contract_details")
        publishPositions(immediate = true)
    }

    private fun applyPortfolioUpdate(
        account: String,
        contract: Contract,
        marketPrice: Double,
        averageCost: Double,
        unrealizedPNL: Double
    ) {
        val symbol = resolveSymbol(contract)
        val key = positionKey(account.orEmpty(), contract, symbol)
        val open = openPositions[key] ?: run {
            IbGatewayLog.portfolioUpdateSkipped(contract)
            return
        }
        openPositions[key] = open.copy(
            ibUnrealizedPnL = unrealizedPNL,
            portfolioMarketPrice = marketPrice,
            portfolioAverageCost = averageCost
        )
        IbGatewayLog.portfolioUpdateApplied(contract, marketPrice, averageCost, unrealizedPNL)
        logPositionDiag(openPositions[key]!!, "portfolio")
        publishPositions(immediate = true)
    }

    private fun doPublishPositions() {
        val published = openPositions.values
            .map { open ->
                val broker = toBrokerPosition(open)
                if (IbGatewayLog.isPositionDiagEnabled()) {
                    logPositionDiag(open, "publish_blotter", broker)
                }
                broker
            }
            .sortedBy { it.symbol }

        _positions.value = published
    }

    private fun scheduleHistoricalFallbackPass() {
        historicalFallbackJob?.cancel()
        historicalFallbackJob = scope.launch {
            delay(HISTORICAL_FALLBACK_DELAY_MS)
            if (!client.isConnected) return@launch
            openPositions.values.forEach { open ->
                if (needsHistoricalFallback(open, open.key)) {
                    requestPacer.enqueue {
                        enqueueHistoricalClose(open.key, open)
                    }
                }
            }
        }
    }

    private fun enqueueHistoricalClose(key: String, open: OpenPosition) {
        if (!client.isConnected || !historicalPendingKeys.add(key)) return
        val reqId = nextHistoricalReqId.getAndIncrement()
        historicalReqIdToKey[reqId] = key
        val contract = IbContractMapper.forDataRequest(open.contract)
        client.reqHistoricalData(
            reqId,
            contract,
            "",
            HISTORICAL_DURATION,
            HISTORICAL_BAR_SIZE,
            HISTORICAL_WHAT_TO_SHOW,
            1,
            1,
            false,
            null
        )
    }

    private fun applyHistoricalClose(reqId: Int) {
        val key = historicalReqIdToKey.remove(reqId) ?: return
        historicalPendingKeys.remove(key)
        val close = historicalLastBarClose.remove(reqId) ?: return
        if (close <= 0.0) return
        historicalPrices[key] = close
        openPositions[key]?.let { open ->
            IbGatewayLog.historicalCloseApplied(open.contract, close)
            logPositionDiag(open, "historical")
        }
        publishPositions(immediate = true)
    }

    private fun needsHistoricalFallback(open: OpenPosition, key: String): Boolean {
        if (historicalPrices.containsKey(key) || historicalPendingKeys.contains(key)) return false
        if (marketPrices.containsKey(key)) return false
        if (resolveMidPrice(key) != null) return false
        val portfolioMarket = open.portfolioMarketPrice
        if (portfolioMarket != null && portfolioMarket > 0.0 &&
            portfolioMarketDistinctFromAvg(open, portfolioMarket)
        ) {
            return false
        }
        return true
    }

    private fun portfolioMarketDistinctFromAvg(open: OpenPosition, portfolioMarket: Double): Boolean {
        val avg = open.portfolioAverageCost ?: open.avgCostRaw
        val epsilon = kotlin.math.max(kotlin.math.abs(avg) * 1e-6, 0.0001)
        return kotlin.math.abs(portfolioMarket - avg) > epsilon
    }

    private fun resolveMarketRawAndSource(open: OpenPosition, key: String): Pair<Double, String> {
        marketPrices[key]?.let { return it to "tick_last" }
        resolveMidPrice(key)?.let { return it to "bid_ask_mid" }
        historicalPrices[key]?.let { return it to "historical_close" }
        open.portfolioMarketPrice?.let { portfolioMarket ->
            if (portfolioMarket > 0.0 && portfolioMarketDistinctFromAvg(open, portfolioMarket)) {
                return portfolioMarket to "portfolio"
            }
        }
        return open.avgCostRaw to "avg_fallback"
    }

    private fun logPositionDiagThrottled(open: OpenPosition, trigger: String) {
        if (!IbGatewayLog.isPositionDiagEnabled()) return
        val now = System.currentTimeMillis()
        val last = lastTickDiagAtMs[open.key] ?: 0L
        if (now - last < TICK_DIAG_MIN_INTERVAL_MS) return
        lastTickDiagAtMs[open.key] = now
        logPositionDiag(open, trigger)
    }

    private fun logPositionDiag(
        open: OpenPosition,
        trigger: String,
        blotter: BrokerPosition? = null
    ) {
        if (!IbGatewayLog.isPositionDiagEnabled()) return
        try {
            IbGatewayLog.positionDiag(buildPositionDiagSnapshot(open, trigger, blotter))
        } catch (e: Exception) {
            IbGatewayLog.callbackFailure("positionDiag:$trigger", e)
        }
    }

    private fun buildPositionDiagSnapshot(
        open: OpenPosition,
        trigger: String,
        blotter: BrokerPosition? = null
    ): PositionDiagSnapshot {
        val key = open.key
        val magnifier = open.priceMagnifier
        val contract = open.contract
        val portfolioMkt = open.portfolioMarketPrice
        val tickMkt = marketPrices[key]
        val bid = bidPrices[key]
        val ask = askPrices[key]
        val mid = resolveMidPrice(key)
        val histMkt = historicalPrices[key]
        val priorClose = priorCloses[key]
        val (marketRaw, marketSource) = resolveMarketRawAndSource(open, key)
        val avgRaw = open.portfolioAverageCost ?: open.avgCostRaw
        val avgSource = if (open.portfolioAverageCost != null) "portfolio" else "position"
        val magnifiers = IbPriceScale.resolvePriceMagnifiers(avgRaw, marketRaw, magnifier)
        val avgMajor = IbPriceScale.toMajorCurrency(avgRaw, magnifiers.avgMagnifier)
        val marketMajor = IbPriceScale.toMajorCurrency(marketRaw, magnifiers.marketMagnifier)
        val spreadRaw = marketRaw - avgRaw
        val pnl = IbPriceScale.unrealizedPnLInPositionCurrency(
            quantity = open.quantity,
            avgCostRaw = avgRaw,
            marketPriceRaw = marketRaw,
            magnifiers = magnifiers
        )
        val pnlMag1 = IbPriceScale.unrealizedPnLInPositionCurrency(
            open.quantity, avgRaw, marketRaw, magnifier = 1
        )
        val pnlMag100 = IbPriceScale.unrealizedPnLInPositionCurrency(
            open.quantity, avgRaw, marketRaw, magnifier = 100
        )
        val expectedGbpIfBothPence =
            IbPriceScale.unrealizedPnLInPositionCurrency(
                open.quantity, avgRaw, marketRaw,
                IbPriceScale.PriceMagnifiers(magnifier, magnifier)
            )
        val published = blotter ?: toBrokerPosition(open)
        val portfolioDistinct = portfolioMkt != null && portfolioMkt > 0.0 &&
            portfolioMarketDistinctFromAvg(open, portfolioMkt)

        return PositionDiagSnapshot(
            trigger = trigger,
            conid = contract.conid(),
            symbol = open.symbol,
            localSymbol = contract.localSymbol().orEmpty(),
            tradingClass = contract.tradingClass().orEmpty(),
            exchange = contract.exchange().orEmpty(),
            primaryExch = contract.primaryExch().orEmpty(),
            currency = contract.currency().orEmpty(),
            quantity = open.quantity,
            priceMagnifierUsed = magnifier,
            avgMagnifierUsed = magnifiers.avgMagnifier,
            marketMagnifierUsed = magnifiers.marketMagnifier,
            contractDetailsMagnifier = open.contractDetailsMagnifier ?: 0,
            defaultMagnifierInferred = IbPriceScale.defaultMagnifier(contract),
            positionAvgCostRaw = open.avgCostRaw,
            portfolioAvgCostRaw = open.portfolioAverageCost,
            portfolioMarketRaw = portfolioMkt,
            tickLastRaw = tickMkt,
            bidRaw = bid,
            askRaw = ask,
            bidAskMidRaw = mid,
            priorCloseRaw = priorClose,
            historicalCloseRaw = histMkt,
            portfolioMarketDistinctFromAvg = portfolioDistinct,
            needsHistoricalFallback = needsHistoricalFallback(open, key),
            avgRawUsed = avgRaw,
            avgSource = avgSource,
            marketRawUsed = marketRaw,
            marketSource = marketSource,
            avgMajor = avgMajor,
            marketMajor = marketMajor,
            spreadRaw = spreadRaw,
            spreadMajor = marketMajor - avgMajor,
            computedPnL = pnl,
            displayCurrency = IbPriceScale.displayCurrency(contract.currency().orEmpty()),
            ibUnrealizedPnL = open.ibUnrealizedPnL,
            pnlIfMagnifier1 = pnlMag1,
            pnlIfMagnifier100 = pnlMag100,
            expectedGbpIfBothPence = expectedGbpIfBothPence,
            blotterAvgPrice = published.avgPrice,
            blotterMarketPrice = published.marketPrice,
            blotterUnrealizedPnL = published.totalUnrealizedPnL
        )
    }

    private fun toBrokerPosition(open: OpenPosition): BrokerPosition {
        val key = open.key
        val contractMagnifier = open.priceMagnifier
        val (marketRaw, _) = resolveMarketRawAndSource(open, key)
        val avgRaw = open.portfolioAverageCost ?: open.avgCostRaw
        val magnifiers = IbPriceScale.resolvePriceMagnifiers(avgRaw, marketRaw, contractMagnifier)
        val avgMajor = IbPriceScale.toMajorCurrency(avgRaw, magnifiers.avgMagnifier)
        val marketMajor = IbPriceScale.toMajorCurrency(marketRaw, magnifiers.marketMagnifier)
        val closeMajor = priorCloses[key]?.let {
            IbPriceScale.toMajorCurrency(it, magnifiers.marketMagnifier)
        }
        val pnl = IbPriceScale.unrealizedPnLInPositionCurrency(
            quantity = open.quantity,
            avgCostRaw = avgRaw,
            marketPriceRaw = marketRaw,
            magnifiers = magnifiers
        )
        val displayCurrency = IbPriceScale.displayCurrency(open.contract.currency().orEmpty())
        return BrokerPosition(
            account = open.account,
            symbol = open.symbol,
            companyName = open.companyName,
            quantity = open.quantity,
            avgPrice = avgMajor,
            marketPrice = marketMajor,
            priorClose = closeMajor,
            totalUnrealizedPnL = pnl,
            currency = displayCurrency
        )
    }

    private fun resolveMidPrice(key: String): Double? {
        val bid = bidPrices[key]
        val ask = askPrices[key]
        if (bid != null && ask != null && bid > 0 && ask > 0) {
            return (bid + ask) / 2.0
        }
        return null
    }

    private fun startReader() {
        if (readerActive) return
        readerActive = true

        val er = EReader(client, signal)
        reader = er
        er.start()

        readerThread = Thread({
            while (readerActive && client.isConnected) {
                signal.waitForSignal()
                try {
                    er.processMsgs()
                } catch (e: Exception) {
                    if (readerActive) {
                        logCallbackFailure("reader", e)
                    }
                }
            }
        }, "ib-gateway-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopReader() {
        readerActive = false
        readerThread?.interrupt()
        readerThread = null
        reader = null
    }

    private fun logCallbackFailure(context: String, e: Exception) {
        IbGatewayLog.callbackFailure(context, e)
    }

    private data class OpenPosition(
        val key: String,
        val account: String,
        val contract: Contract,
        val symbol: String,
        val companyName: String,
        val quantity: Int,
        val avgCostRaw: Double,
        val priceMagnifier: Int = 1,
        val contractDetailsMagnifier: Int? = null,
        val ibUnrealizedPnL: Double? = null,
        val portfolioMarketPrice: Double? = null,
        val portfolioAverageCost: Double? = null,
        val needsContractDetails: Boolean
    )

    private companion object {
        const val MKT_DATA_REQ_ID_START = 10_000
        const val CONTRACT_DETAILS_REQ_ID_START = 20_000
        const val HISTORICAL_REQ_ID_START = 30_000
        const val TOUCH_TURN_HISTORICAL_REQ_ID_START = 50_000
        const val TOUCH_TURN_HISTORICAL_DURATION = "1 D"
        const val TOUCH_TURN_HISTORICAL_BAR_SIZE = "15 mins"
        const val TOUCH_TURN_HISTORICAL_TIMEOUT_MS = 45_000L
        const val ADR_HISTORICAL_REQ_ID_START = 55_000
        const val ADR_HISTORICAL_DURATION = "20 D"
        const val ADR_HISTORICAL_BAR_SIZE = "1 day"
        const val ADR_HISTORICAL_TIMEOUT_MS = 45_000L
        const val PUBLISH_THROTTLE_MS = 300L
        const val HISTORICAL_FALLBACK_DELAY_MS = 4_000L
        const val HISTORICAL_DURATION = "2 D"
        const val HISTORICAL_BAR_SIZE = "1 day"
        const val HISTORICAL_WHAT_TO_SHOW = "TRADES"
        const val TICK_DIAG_MIN_INTERVAL_MS = 5_000L
        /** Delayed data; when the market is closed, shows the last delayed quote (not zero). */
        const val MARKET_DATA_TYPE_DELAYED_FROZEN = 4

        val INFO_ERROR_CODES = setOf(
            2104, 2106, 2107, 2158,
            2119, // Market data farm is connecting
            10167 // Requested market data not subscribed; displaying delayed data
        )
        val FATAL_ERROR_CODES = setOf(502, 504, 1100, 1101, 1102)

        fun needsContractDetails(companyName: String, symbol: String): Boolean =
            companyName == symbol || companyName.startsWith("conid:")

        fun resolveSymbol(contract: Contract): String {
            val symbol = contract.symbol()
            if (!symbol.isNullOrBlank()) return symbol
            val local = contract.localSymbol()
            if (!local.isNullOrBlank()) return local
            return if (contract.conid() > 0) "conid:${contract.conid()}" else "unknown"
        }

        fun resolveCompanyName(contract: Contract, symbol: String): String {
            val description = contract.description()
            if (!description.isNullOrBlank()) return description
            val local = contract.localSymbol()
            if (!local.isNullOrBlank()) return local
            return symbol
        }

        fun positionKey(account: String, contract: Contract, symbol: String): String {
            val conid = contract.conid()
            if (conid > 0) return "$account|conid:$conid"
            return "$account|$symbol|${contract.getSecType().orEmpty()}|${contract.exchange().orEmpty()}"
        }

        fun barTimeSortKey(time: String?): String = time?.trim().orEmpty()

        fun sessionDayYyyyMmDd(symbol: String?): String {
            val zone = if (symbol != null && SymbolMarkets.isHongKong(symbol)) {
                ZoneId.of("Asia/Hong_Kong")
            } else {
                ZoneId.systemDefault()
            }
            return LocalDate.now(zone).format(DateTimeFormatter.BASIC_ISO_DATE)
        }
    }
}

private fun Bar.toTouchTurnOhlcBar(): OhlcBar = OhlcBar(
    open = open(),
    high = high(),
    low = low(),
    close = close(),
    time = time()
)
