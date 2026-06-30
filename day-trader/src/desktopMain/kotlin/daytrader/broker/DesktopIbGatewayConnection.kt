package daytrader.broker

import com.ib.client.Bar
import com.ib.client.CommissionAndFeesReport
import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.Decimal
import com.ib.client.DefaultEWrapper
import com.ib.client.Execution
import com.ib.client.ExecutionFilter
import com.ib.client.EClientSocket
import com.ib.client.EJavaSignal
import com.ib.client.EReader
import com.ib.client.Order
import com.ib.client.OrderCancel
import com.ib.client.OrderState
import com.ib.client.Types
import com.ib.client.TickAttrib
import com.ib.client.TickType
import com.ib.client.protobuf.AccountDataEndProto
import com.ib.client.protobuf.HistoricalDataEndProto
import com.ib.client.protobuf.HistoricalDataProto
import com.ib.client.protobuf.PortfolioValueProto
import com.ib.client.protobuf.PositionEndProto
import com.ib.client.protobuf.PositionProto
import com.ib.client.protobuf.TickPriceProto
import com.ib.client.protobuf.TickSizeProto
import com.ib.client.protobuf.TickStringProto
import daytrader.domain.InstrumentMarketResolver
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnCandleLog
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.requiresDailyHistoricalBootstrap
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.InstrumentIdentity
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BlockingGatewayQueues
import daytrader.gateway.BrokerAdapter
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import daytrader.gateway.IbStreamingMarketDataType
import daytrader.gateway.LiveQuote
import daytrader.gateway.QueuedBrokerGateway
import daytrader.gateway.WorkingOrder
import daytrader.gateway.TouchTurnBracketAck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
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
    private val queues: BlockingGatewayQueues,
    private val config: IbGatewayConfig = IbGatewayConfig.fromEnvironment(),
    private val connectionMode: IbConnectionMode = IbConnectionMode.FULL,
    private val onLiveQuote: ((symbol: String, quote: LiveQuote, priorClose: Double?) -> Unit)? = null,
    private val quoteBus: daytrader.marketdata.MarketQuoteBus? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : BrokerAdapter, DefaultEWrapper() {

    private val marketDataOnly: Boolean
        get() = connectionMode == IbConnectionMode.MARKET_DATA_ONLY

    override val brokerId: BrokerId = BrokerId.INTERACTIVE_BROKERS

    @Volatile
    private var connectionState: GatewayConnectionState = GatewayConnectionState.Disconnected

    @Volatile
    private var streamingMarketDataType: IbStreamingMarketDataType = IbStreamingMarketDataType.DEFAULT

    private var commandLoopJob: Job? = null

    private val signal = EJavaSignal()
    private val client = EClientSocket(this, signal)
    private val connectMutex = Mutex()
    private val requestPacer = IbRequestPacer(scope)
    private val touchTurnBracketCoordinator = IbTouchTurnBracketCoordinator(scope)
    private val executionsRefresh = IbCoalescedPacedRequest(
        scope = scope,
        pacer = requestPacer,
        minIntervalMs = IbRateLimits.executionsRefreshIntervalMs(),
        action = ::enqueueRequestExecutions
    )

    private var reader: EReader? = null
    @Volatile
    private var readerActive = false
    private var readerThread: Thread? = null
    @Volatile
    private var enrichmentScheduled = false
    @Volatile
    private var positionsLoadFinished = false

    private val openPositions = ConcurrentHashMap<String, OpenPosition>()
    private val openOrdersById = ConcurrentHashMap<Int, WorkingOrder>()
    private val fillsByExecId = ConcurrentHashMap<String, BrokerFill>()
    /** Maps fill [BrokerFill.orderId] to bracket parent order id (0 = entry/parent leg). */
    private val orderParentByOrderId = ConcurrentHashMap<Int, Int>()
    private val trailAdjustmentOrderIds = ConcurrentHashMap.newKeySet<Int>()
    private val stopTrailParamsByOrderId = ConcurrentHashMap<Int, StopTrailParams>()

    private data class StopTrailParams(
        val triggerPrice: Double,
        val trailAmount: Double
    )
    private val executionsReqId = AtomicInteger(9_001)
    private val nextOrderId = AtomicInteger(0)
    @Volatile
    private var openOrdersLoadFinished = false
    private val marketPrices = ConcurrentHashMap<String, Double>()
    private val priorCloses = ConcurrentHashMap<String, Double>()
    private val bidPrices = ConcurrentHashMap<String, Double>()
    private val askPrices = ConcurrentHashMap<String, Double>()
    private val lastTradePrices = ConcurrentHashMap<String, Double>()
    private val pendingVolumeDeltaByKey = ConcurrentHashMap<String, Double>()
    private val cumulativeVolumeByKey = ConcurrentHashMap<String, Double>()
    private val quotesBySymbol = ConcurrentHashMap<String, LiveQuote>()
    private var quotesPublishJob: Job? = null

    /** IB reqId -> logical keys that share this streaming subscription. */
    private val mktDataReqIdToLogicalKeys = ConcurrentHashMap<Int, MutableSet<String>>()
    private val keyToMktDataReqId = ConcurrentHashMap<String, Int>()
    private val canonicalKeyToReqId = ConcurrentHashMap<String, Int>()
    private val reqIdToCanonicalKey = ConcurrentHashMap<Int, String>()
    private val contractDetailsReqIdToKey = ConcurrentHashMap<Int, String>()
    private val instrumentResolveIbReqToGatewayReq = ConcurrentHashMap<Int, Long>()
    private val instrumentResolveBatches = ConcurrentHashMap<Long, InstrumentResolveBatch>()
    private val instrumentResolveCompleted = ConcurrentHashMap.newKeySet<Long>()
    private val nextInstrumentResolveReqId = AtomicInteger(INSTRUMENT_RESOLVE_REQ_ID_START)
    private val historicalPrices = ConcurrentHashMap<String, Double>()
    private val historicalReqIdToKey = ConcurrentHashMap<Int, String>()
    private val historicalPendingKeys = ConcurrentHashMap.newKeySet<String>()
    private val historicalLastBarClose = ConcurrentHashMap<Int, Double>()
    private val touchTurnHistoricalBars = ConcurrentHashMap<Int, MutableList<Bar>>()
    private val touchTurnHistoricalSymbol = ConcurrentHashMap<Int, String>()
    private val touchTurnHistoricalMarketZoneId = ConcurrentHashMap<Int, String>()
    private val touchTurnHistoricalAllowMissingToday = ConcurrentHashMap<Int, Boolean>()
    private val touchTurnHistoricalRules = ConcurrentHashMap<Int, TouchTurnRuleConfig>()
    private val touchTurnGatewayRequestId = ConcurrentHashMap<Int, Long>()
    private val adrHistoricalBars = ConcurrentHashMap<Int, MutableList<Bar>>()
    private val adrHistoricalSymbol = ConcurrentHashMap<Int, String>()
    private val adrHistoricalMarketZoneId = ConcurrentHashMap<Int, String>()
    private val adrHistoricalCacheKey = ConcurrentHashMap<Int, String>()
    private val adrGatewayRequestId = ConcurrentHashMap<Int, Long>()
    private val adrCacheByKey = ConcurrentHashMap<String, CachedAdr>()
    private val historicalTimeoutJobs = ConcurrentHashMap<Int, Job>()
    private val lastTickDiagAtMs = ConcurrentHashMap<String, Long>()
    @Volatile
    private var positionRefreshKeysBefore: Set<String>? = null
    /** Keys for symbol-only streaming (paper/hybrid emulator marks). */
    private val streamSymbolByMktDataKey = ConcurrentHashMap<String, String>()
    private val pendingStreamSymbols = ConcurrentHashMap.newKeySet<String>()
    private val streamSubscriptionRefCount = ConcurrentHashMap<String, Int>()
    private val nextMktDataReqId = AtomicInteger(MKT_DATA_REQ_ID_START)
    private val nextContractDetailsReqId = AtomicInteger(CONTRACT_DETAILS_REQ_ID_START)
    private val nextHistoricalReqId = AtomicInteger(HISTORICAL_REQ_ID_START)
    private val nextTouchTurnHistoricalReqId = AtomicInteger(TOUCH_TURN_HISTORICAL_REQ_ID_START)
    private val nextAdrHistoricalReqId = AtomicInteger(ADR_HISTORICAL_REQ_ID_START)
    private val nextReversalScoreMktDataReqId = AtomicInteger(REVERSAL_SCORE_MKT_DATA_REQ_ID_START)
    private val nextOneShotGatewayRequestId = AtomicLong(9_000_000_000L)
    private val oneShotFirstCandle = ConcurrentHashMap<Long, CompletableDeferred<Result<OhlcBar>>>()
    private val oneShotAdr = ConcurrentHashMap<Long, CompletableDeferred<Result<Double>>>()
    private val oneShotSignalContext = ConcurrentHashMap<Long, CompletableDeferred<Result<TouchTurnSignalContext>>>()
    private val pendingTouchTurnSignalContext = ConcurrentHashMap<Long, PendingTouchTurnSignalContext>()
    private val adrReqIdForSignalContextGateway = ConcurrentHashMap<Int, Long>()

    private val reversalScoreHandler = ReversalScoreIbHandler(
        scope = scope,
        requestPacer = requestPacer,
        clientProvider = { client },
        isConnected = { client.isConnected },
        nextHistoricalReqId = { nextHistoricalReqId.getAndIncrement() },
        nextMktDataReqId = { nextReversalScoreMktDataReqId.getAndIncrement() },
        emit = ::emit
    )

    private var publishDebounceJob: Job? = null
    private var historicalFallbackJob: Job? = null

    override fun start() {
        commandLoopJob = scope.launch(Dispatchers.IO) {
            while (true) {
                when (val command = queues.outbound.take()) {
                    GatewayCommand.Connect -> scope.launch {
                        connectMutex.withLock { performConnect() }
                    }
                    GatewayCommand.Disconnect -> scope.launch {
                        connectMutex.withLock { performDisconnect() }
                    }
                    GatewayCommand.Reconnect -> scope.launch {
                        connectMutex.withLock {
                            performDisconnect()
                            delay(IbRequestPacer.RECONNECT_DELAY_MS)
                            performConnect()
                        }
                    }
                    GatewayCommand.Shutdown -> {
                        runBlocking {
                            connectMutex.withLock { performDisconnect() }
                        }
                        return@launch
                    }
                    GatewayCommand.ResetSessionState -> Unit
                    is GatewayCommand.PruneSymbolSessionState -> Unit
                    is GatewayCommand.EnsureStreamingMarketData -> Unit
                    is GatewayCommand.SeedSyntheticQuote -> Unit
                    is GatewayCommand.FetchFourteenDayAdr ->
                        scope.launch {
                            requestFourteenDayAdr(command.requestId, command.symbol, command.instrument)
                        }
                    is GatewayCommand.FetchLatestDailyClose ->
                        scope.launch {
                            requestLatestDailyClose(command.requestId, command.symbol, command.instrument)
                        }
                    is GatewayCommand.FetchReversalScoreSymbolSnapshot ->
                        scope.launch {
                            reversalScoreHandler.requestSymbolSnapshot(
                                command.requestId,
                                command.symbol,
                                command.instrument
                            )
                        }
                    is GatewayCommand.FetchReversalScoreMacroVolatility ->
                        scope.launch {
                            reversalScoreHandler.requestMacroVolatility(command.requestId)
                        }
                    is GatewayCommand.FetchSpyRegimeSnapshot ->
                        scope.launch {
                            reversalScoreHandler.requestSpyRegime(command.requestId)
                        }
                    is GatewayCommand.FetchHomeMarketRegimeSnapshot ->
                        scope.launch {
                            reversalScoreHandler.requestHomeMarketRegimeSnapshot(
                                command.requestId,
                                command.marketZoneId
                            )
                        }
                    is GatewayCommand.FetchFirstFifteenMinuteCandle ->
                        scope.launch {
                            requestFirstFifteenMinuteCandle(
                                command.requestId,
                                command.symbol,
                                command.instrument
                            )
                        }
                    is GatewayCommand.FetchTouchTurnSignalContext ->
                        scope.launch {
                            emit(
                                GatewayEvent.TouchTurnSignalContextReady(
                                    command.requestId,
                                    fetchTouchTurnSignalContextComposite(
                                        command.symbol,
                                        command.instrument,
                                        command.marketZoneId,
                                        command.allowMissingTodayOpeningBar,
                                        command.rules
                                    )
                                )
                            )
                        }
                    is GatewayCommand.CancelOrder -> {
                        if (!marketDataOnly) {
                            scope.launch { cancelWorkingOrder(command.orderId) }
                        }
                    }
                    is GatewayCommand.ResolveInstrument ->
                        scope.launch { requestInstrumentResolve(command.requestId, command.symbol) }
                    is GatewayCommand.ResizeTouchTurnBracket -> {
                        if (!marketDataOnly) {
                            resizeTouchTurnBracket(command.requestId, command.request)
                        } else {
                            emit(
                                GatewayEvent.TouchTurnBracketResized(
                                    requestId = command.requestId,
                                    result = Result.failure(IllegalStateException("market_data_only_connection"))
                                )
                            )
                        }
                    }
                    is GatewayCommand.PlaceTouchTurnBracket -> {
                        if (marketDataOnly) {
                            IbGatewayLog.touchTurnBracketSkipped(
                                "Market-data-only IB connection (use emulator for orders)"
                            )
                            emit(
                                GatewayEvent.TouchTurnBracketPlaced(
                                    daytrader.gateway.TouchTurnBracketAck(
                                        symbol = SymbolMarkets.normalizeSymbol(command.plan.symbol),
                                        orderIds = emptyList(),
                                        result = Result.failure(
                                            IllegalStateException("market_data_only_connection")
                                        ),
                                        plan = command.plan
                                    )
                                )
                            )
                        } else {
                            placeTouchTurnBracket(command.plan)
                        }
                    }
                    is GatewayCommand.CancelOpenOrdersForSymbol -> {
                        if (!marketDataOnly) {
                            cancelOpenOrdersForSymbol(command.symbol)
                        }
                    }
                    is GatewayCommand.CloseOpenPositionForSymbol -> {
                        if (!marketDataOnly) {
                            closeOpenPositionForSymbol(command.symbol)
                        }
                    }
                    is GatewayCommand.FlattenSymbolForSymbol -> {
                        if (!marketDataOnly) {
                            flattenSymbolForSymbol(command.symbol)
                        }
                    }
                    GatewayCommand.RequestExecutions -> {
                        if (!marketDataOnly) scheduleExecutionsRefresh()
                    }
                }
            }
        }
    }

    override fun shutdown() {
        queues.outbound.offer(GatewayCommand.Shutdown)
        commandLoopJob?.cancel()
        commandLoopJob = null
        runBlocking {
            connectMutex.withLock { performDisconnect() }
        }
    }

    private fun emit(event: GatewayEvent) {
        queues.inbound.offer(event)
    }

    private fun emitConnectionState(state: GatewayConnectionState) {
        connectionState = state
        emit(GatewayEvent.ConnectionStateChanged(state))
    }

    private suspend fun requestFourteenDayAdr(
        gatewayRequestId: Long,
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        if (!client.isConnected) {
            deliverAdrReady(
                gatewayRequestId,
                Result.failure(IllegalStateException("Not connected to IB Gateway"))
            )
            return
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            deliverAdrReady(
                gatewayRequestId,
                Result.failure(IllegalArgumentException("Symbol is blank"))
            )
            return
        }
        val marketZoneId = SymbolMarkets.marketZoneIdForSession(trimmed, instrument)
        val sessionDay = sessionDayYyyyMmDd(marketZoneId)
        val cacheKey = adrCacheKey(trimmed, instrument)
        adrCacheByKey[cacheKey]?.let { cached ->
            if (cached.sessionDay == sessionDay) {
                IbGatewayLog.debug("ADR cache hit symbol=$trimmed sessionDay=$sessionDay")
                deliverAdrReady(gatewayRequestId, cached.result)
                return
            }
        }
        val reqId = nextAdrHistoricalReqId.getAndIncrement()
        adrGatewayRequestId[reqId] = gatewayRequestId
        adrHistoricalSymbol[reqId] = trimmed
        adrHistoricalMarketZoneId[reqId] = marketZoneId
        adrHistoricalCacheKey[reqId] = cacheKey
        val contract = IbContractMapper.contractForSymbol(trimmed, instrument)
        scheduleHistoricalRequestTimeout(reqId, ADR_HISTORICAL_TIMEOUT_MS) {
            failAdrHistorical(reqId, "14-day ADR request timed out after ${ADR_HISTORICAL_TIMEOUT_MS / 1000}s")
            paced {
                if (client.isConnected) {
                    runCatching { client.cancelHistoricalData(reqId) }
                }
            }
        }
        requestPacer.enqueue {
            if (!client.isConnected) {
                clearHistoricalRequestTimeout(reqId)
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
    }

    private suspend fun requestFirstFifteenMinuteCandle(
        gatewayRequestId: Long,
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        if (!client.isConnected) {
            deliverFirstCandleReady(
                gatewayRequestId,
                Result.failure(IllegalStateException("Not connected to IB Gateway"))
            )
            return
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            deliverFirstCandleReady(
                gatewayRequestId,
                Result.failure(IllegalArgumentException("Symbol is blank"))
            )
            return
        }
        val reqId = nextTouchTurnHistoricalReqId.getAndIncrement()
        val marketZoneId = SymbolMarkets.marketZoneIdForSession(trimmed, instrument)
        touchTurnGatewayRequestId[reqId] = gatewayRequestId
        touchTurnHistoricalSymbol[reqId] = trimmed
        touchTurnHistoricalMarketZoneId[reqId] = marketZoneId
        val contract = IbContractMapper.contractForSymbol(trimmed, instrument)
        scheduleHistoricalRequestTimeout(reqId, TOUCH_TURN_HISTORICAL_TIMEOUT_MS) {
            cancelTouchTurnHistorical(reqId)
            failTouchTurnHistorical(
                reqId,
                "First 15-minute candle request timed out after ${TOUCH_TURN_HISTORICAL_TIMEOUT_MS / 1000}s"
            )
        }
        requestPacer.enqueue {
            if (!client.isConnected) {
                clearHistoricalRequestTimeout(reqId)
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
    }

    private suspend fun requestInstrumentResolve(gatewayRequestId: Long, symbol: String) {
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            emit(
                GatewayEvent.InstrumentResolved(
                    gatewayRequestId,
                    Result.failure(IllegalArgumentException("Symbol is blank"))
                )
            )
            return
        }
        if (!client.isConnected) {
            emit(
                GatewayEvent.InstrumentResolved(
                    gatewayRequestId,
                    Result.failure(IllegalStateException("Not connected to IB Gateway"))
                )
            )
            return
        }
        val contracts = IbContractMapper.contractDetailsLookupContracts(trimmed)
        val batch = InstrumentResolveBatch(gatewayRequestId, trimmed)
        instrumentResolveBatches[gatewayRequestId] = batch
        IbGatewayLog.instrumentResolve(
            "IB batch start gatewayReqId=$gatewayRequestId symbol=$trimmed legs=${contracts.size}"
        )
        contracts.forEach { contract ->
            val ibReqId = nextInstrumentResolveReqId.getAndIncrement()
            batch.pendingIbReqIds.add(ibReqId)
            instrumentResolveIbReqToGatewayReq[ibReqId] = gatewayRequestId
            IbGatewayLog.instrumentResolve(
                "IB leg request ibReqId=$ibReqId ${IbContractMapper.describe(contract)}"
            )
            requestPacer.enqueue {
                if (!client.isConnected) {
                    failInstrumentResolveBatch(gatewayRequestId, "Disconnected before contract details")
                    return@enqueue
                }
                client.reqContractDetails(ibReqId, contract)
            }
        }
        scope.launch {
            delay(INSTRUMENT_RESOLVE_TIMEOUT_MS)
            if (instrumentResolveBatches.containsKey(gatewayRequestId)) {
                emitInstrumentResolveBatch(gatewayRequestId)
            }
        }
    }

    private fun completeInstrumentResolveIfPending(
        reqId: Int,
        contractDetails: ContractDetails
    ): Boolean {
        val gatewayRequestId = instrumentResolveIbReqToGatewayReq[reqId] ?: return false
        val batch = instrumentResolveBatches[gatewayRequestId] ?: return false
        val contract = contractDetails.contract()
        val companyName = extractCompanyName(contractDetails, contract)
        if (!companyName.isNullOrBlank()) {
            batch.companyName = companyName
        }
        val orderSizeRules = IbOrderSizeRules.fromContractDetails(contractDetails)
        val snapshot = InstrumentMarketResolver.ContractSnapshot(
            symbol = contract.symbol().orEmpty(),
            exchange = contract.exchange(),
            primaryExch = contract.primaryExch(),
            currency = contract.currency(),
            companyName = batch.companyName,
            minOrderSize = orderSizeRules.minOrderSize,
            orderSizeIncrement = orderSizeRules.orderSizeIncrement
        )
        val conId = contract.conid().takeIf { it > 0 }?.toLong()
        val resolved = InstrumentMarketResolver.fromIbContract(snapshot).copy(
            identity = daytrader.domain.InstrumentIdentity.fromContractSnapshot(snapshot, conId)
        )
        batch.candidates[resolved.identity!!.dedupeKey()] = resolved
        IbGatewayLog.instrumentResolve(
            "IB contract detail ibReqId=$reqId gatewayReqId=$gatewayRequestId " +
                "symbol=${snapshot.symbol} exchange=${snapshot.exchange} " +
                "primary=${snapshot.primaryExch} currency=${snapshot.currency} conId=$conId " +
                "minOrderSize=${orderSizeRules.minOrderSize} orderSizeIncrement=${orderSizeRules.orderSizeIncrement} " +
                "venue=${resolved.venueLabel} batchSize=${batch.candidates.size}"
        )
        return true
    }

    override fun contractDetailsEnd(reqId: Int) {
        try {
            if (instrumentResolveIbReqToGatewayReq.containsKey(reqId)) {
                markInstrumentResolveLegFinished(reqId)
            }
        } catch (e: Exception) {
            logCallbackFailure("contractDetailsEnd", e)
        }
    }

    private fun markInstrumentResolveLegFinished(ibReqId: Int) {
        val gatewayRequestId = instrumentResolveIbReqToGatewayReq.remove(ibReqId) ?: return
        val batch = instrumentResolveBatches[gatewayRequestId] ?: return
        batch.pendingIbReqIds.remove(ibReqId)
        IbGatewayLog.instrumentResolve(
            "IB leg finished ibReqId=$ibReqId gatewayReqId=$gatewayRequestId " +
                "symbol=${batch.symbol} pendingLegs=${batch.pendingIbReqIds.size} " +
                "batchCandidates=${batch.candidates.size}"
        )
        if (batch.pendingIbReqIds.isEmpty()) {
            scheduleInstrumentResolveBatchEmit(gatewayRequestId)
        }
    }

    private fun scheduleInstrumentResolveBatchEmit(gatewayRequestId: Long) {
        val batch = instrumentResolveBatches[gatewayRequestId] ?: return
        batch.finishJob?.cancel()
        batch.finishJob = scope.launch {
            delay(INSTRUMENT_RESOLVE_DEBOUNCE_MS)
            emitInstrumentResolveBatch(gatewayRequestId)
        }
    }

    private fun emitInstrumentResolveBatch(gatewayRequestId: Long) {
        val batch = instrumentResolveBatches.remove(gatewayRequestId) ?: return
        batch.finishJob?.cancel()
        batch.pendingIbReqIds.forEach { instrumentResolveIbReqToGatewayReq.remove(it) }
        if (!instrumentResolveCompleted.add(gatewayRequestId)) return
        val rawCount = batch.candidates.size
        val candidates = daytrader.domain.InstrumentListingCandidates.prepareForUi(batch.candidates.values)
        val labels = candidates.map(daytrader.domain.InstrumentListingCandidates::listingLabel)
        if (candidates.isEmpty()) {
            daytrader.domain.InstrumentResolveLog.resolveFinished(
                symbol = batch.symbol,
                success = false,
                rawCount = rawCount,
                uiCount = 0,
                listings = emptyList(),
                error = "No contract details returned"
            )
            emit(
                GatewayEvent.InstrumentResolved(
                    gatewayRequestId,
                    Result.failure(IllegalStateException("No contract details returned"))
                )
            )
            return
        }
        daytrader.domain.InstrumentResolveLog.resolveFinished(
            symbol = batch.symbol,
            success = true,
            rawCount = rawCount,
            uiCount = candidates.size,
            listings = labels
        )
        emit(
            GatewayEvent.InstrumentResolved(
                gatewayRequestId,
                Result.success(daytrader.domain.InstrumentResolution(candidates))
            )
        )
    }

    private fun extractCompanyName(contractDetails: ContractDetails, contract: Contract): String? =
        contractDetails.longName()?.takeIf { it.isNotBlank() }
            ?: contractDetails.marketName()?.takeIf { it.isNotBlank() }
            ?: contract.description()?.takeIf { it.isNotBlank() }

    private fun failInstrumentResolveBatch(gatewayRequestId: Long, message: String) {
        val batch = instrumentResolveBatches.remove(gatewayRequestId) ?: return
        batch.finishJob?.cancel()
        batch.pendingIbReqIds.forEach { instrumentResolveIbReqToGatewayReq.remove(it) }
        if (instrumentResolveCompleted.add(gatewayRequestId)) {
            daytrader.domain.InstrumentResolveLog.resolveFinished(
                symbol = batch.symbol,
                success = false,
                rawCount = batch.candidates.size,
                uiCount = 0,
                listings = emptyList(),
                error = message
            )
            emit(
                GatewayEvent.InstrumentResolved(
                    gatewayRequestId,
                    Result.failure(IllegalStateException(message))
                )
            )
        }
    }

    override fun nextValidId(orderId: Int) {
        nextOrderId.set(orderId)
        emitConnectionState(GatewayConnectionState.Connected)
        IbGatewayLog.nextValidId(orderId)
        paced {
            client.reqMarketDataType(streamingMarketDataType.ibCode)
        }
        if (marketDataOnly) {
            resubscribeAllStreamingSymbols()
            return
        }
        paced { client.reqAccountUpdates(true, config.accountCode) }
        paced { requestPositions() }
        paced { requestOpenOrders() }
        scheduleExecutionsRefresh()
        resubscribeAllStreamingSymbols()
    }

    override fun execDetails(reqId: Int, contract: Contract, execution: Execution) {
        try {
            applyExecution(contract, execution)
        } catch (e: Exception) {
            logCallbackFailure("execDetails", e)
        }
    }

    override fun execDetailsEnd(reqId: Int) {
        try {
            publishFills()
            IbGatewayLog.executionsLoadComplete(fillsByExecId.size)
        } catch (e: Exception) {
            logCallbackFailure("execDetailsEnd", e)
        }
    }

    override fun commissionAndFeesReport(report: CommissionAndFeesReport) {
        try {
            applyCommissionReport(report)
        } catch (e: Exception) {
            logCallbackFailure("commissionAndFeesReport", e)
        }
    }

    override fun openOrder(orderId: Int, contract: Contract, order: Order, orderState: OrderState) {
        try {
            val status = orderStatusLabel(orderState)
            val isWorking = isWorkingOpenOrder(status, order)
            applyOpenOrder(orderId, contract, order, orderState)
            notifyTouchTurnBracketOpenOrder(orderId, isWorking)
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
            applyOrderStatus(orderId, status, filled, remaining, permId, parentId)
            notifyTouchTurnBracketOrderStatus(orderId, status, remaining)
            if (status.equals("Filled", ignoreCase = true)) {
                scheduleExecutionsRefresh()
            }
        } catch (e: Exception) {
            logCallbackFailure("orderStatus", e)
        }
    }

    override fun connectionClosed() {
        stopReader()
        clearPositionState()
        if (connectionState !is GatewayConnectionState.Disconnected) {
            emitConnectionState(GatewayConnectionState.Disconnected)
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

    override fun tickSize(tickerId: Int, field: Int, size: Decimal) {
        try {
            reversalScoreHandler.onTickSize(tickerId, field, size)
            applyTickSize(tickerId, field, size)
        } catch (e: Exception) {
            logCallbackFailure("tickSize", e)
        }
    }

    override fun tickSizeProtoBuf(tickSize: TickSizeProto.TickSize) {
        try {
            if (!tickSize.hasReqId() || !tickSize.hasTickType() || !tickSize.hasSize()) return
            val size = Decimal.parse(tickSize.size)
            reversalScoreHandler.onTickSize(tickSize.reqId, tickSize.tickType, size)
            applyTickSize(tickSize.reqId, tickSize.tickType, size)
        } catch (e: Exception) {
            logCallbackFailure("tickSizeProtoBuf", e)
        }
    }

    override fun tickString(tickerId: Int, tickType: Int, value: String) {
        try {
            applyTickString(tickerId, tickType, value)
        } catch (e: Exception) {
            logCallbackFailure("tickString", e)
        }
    }

    override fun tickStringProtoBuf(tickString: TickStringProto.TickString) {
        try {
            if (!tickString.hasReqId() || !tickString.hasTickType() || !tickString.hasValue()) return
            applyTickString(tickString.reqId, tickString.tickType, tickString.value)
        } catch (e: Exception) {
            logCallbackFailure("tickStringProtoBuf", e)
        }
    }

    override fun tickPrice(tickerId: Int, field: Int, price: Double, attribs: TickAttrib?) {
        try {
            reversalScoreHandler.onTickPrice(tickerId, field, price)
            applyTickPrice(tickerId, field, price)
        } catch (e: Exception) {
            logCallbackFailure("tickPrice", e)
        }
    }

    override fun tickPriceProtoBuf(tickPrice: TickPriceProto.TickPrice) {
        try {
            if (!tickPrice.hasReqId() || !tickPrice.hasTickType() || !tickPrice.hasPrice()) return
            reversalScoreHandler.onTickPrice(tickPrice.reqId, tickPrice.tickType, tickPrice.price)
            applyTickPrice(tickPrice.reqId, tickPrice.tickType, tickPrice.price)
        } catch (e: Exception) {
            logCallbackFailure("tickPriceProtoBuf", e)
        }
    }

    override fun tickSnapshotEnd(tickerId: Int) {
        reversalScoreHandler.onSnapshotEnd(tickerId)
        IbGatewayLog.marketDataSnapshotComplete(tickerId)
    }

    override fun contractDetails(reqId: Int, contractDetails: ContractDetails) {
        try {
            if (completeInstrumentResolveIfPending(reqId, contractDetails)) return
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
            if (reversalScoreHandler.onHistoricalData(reqId, bar)) return
            if (isAdrHistoricalReqId(reqId)) {
                if (bar.high() > 0.0 && bar.low() > 0.0) {
                    adrHistoricalBars.getOrPut(reqId) { mutableListOf() }.add(bar)
                }
                return
            }
            if (touchTurnGatewayRequestId.containsKey(reqId)) {
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
            if (reversalScoreHandler.onHistoricalDataEnd(reqId)) return
            if (isAdrHistoricalReqId(reqId)) {
                completeAdrHistorical(reqId)
                return
            }
            if (touchTurnGatewayRequestId.containsKey(reqId)) {
                completeTouchTurnHistorical(reqId)
                return
            }
            applyHistoricalClose(reqId)
        } catch (e: Exception) {
            logCallbackFailure("historicalDataEnd", e)
        }
    }

    private fun isAdrHistoricalReqId(reqId: Int): Boolean =
        adrGatewayRequestId.containsKey(reqId) || adrReqIdForSignalContextGateway.containsKey(reqId)

    override fun historicalDataProtoBuf(historicalData: HistoricalDataProto.HistoricalData) {
        try {
            if (!historicalData.hasReqId()) return
            val reqId = historicalData.reqId
            if (reversalScoreHandler.onHistoricalDataProtoBuf(reqId, historicalData)) return
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
                val reqId = historicalDataEnd.reqId
                if (reversalScoreHandler.onHistoricalDataEnd(reqId)) return
                applyHistoricalClose(reqId)
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

        if (reversalScoreHandler.onIbError(reqId, errorCode, errorMsg ?: "")) {
            return
        }

        historicalReqIdToKey[reqId]?.let { key ->
            if (key.startsWith(WATCHLIST_HISTORICAL_KEY_PREFIX) && errorCode !in HISTORICAL_BENIGN_ERROR_CODES) {
                key.removePrefix(WATCHLIST_HISTORICAL_KEY_PREFIX).toLongOrNull()?.let { gatewayRequestId ->
                    deliverLatestDailyCloseReady(
                        gatewayRequestId,
                        Result.failure(IllegalStateException(errorMsg))
                    )
                }
            }
            historicalReqIdToKey.remove(reqId)
            historicalLastBarClose.remove(reqId)
            historicalPendingKeys.remove(key)
        }

        if (errorCode !in HISTORICAL_BENIGN_ERROR_CODES) {
            failTouchTurnHistorical(reqId, errorMsg)
            failAdrHistorical(reqId, errorMsg)
        }

        if (instrumentResolveIbReqToGatewayReq.containsKey(reqId)) {
            if (errorCode == 200) {
                IbGatewayLog.instrumentResolve(
                    "IB leg error ibReqId=$reqId code=$errorCode msg=${errorMsg ?: ""} (leg skipped)"
                )
            }
            markInstrumentResolveLegFinished(reqId)
        }

        if (reqId > 0) {
            touchTurnBracketCoordinator.onOrderError(reqId, errorMsg ?: "") { pending, reason ->
                emitTouchTurnBracketFailure(pending, reason)
            }
        }

        when {
            errorCode == 100 -> {
                requestPacer.applyRateLimitBackoff()
                emitConnectionState(
                    GatewayConnectionState.Error(
                        "IB API rate limit exceeded (50 messages/sec). Slowing down requests."
                    )
                )
            }
            errorCode == 101 -> emitConnectionState(
                GatewayConnectionState.Error(
                    "IB market data line limit reached. Reduce active subscriptions or close other clients."
                )
            )
            errorCode == 502 -> emitConnectionState(
                GatewayConnectionState.Error(
                    "Cannot connect to IB Gateway at ${config.endpoint}. Is Gateway running and API enabled?"
                )
            )
            errorCode in FATAL_ERROR_CODES -> emitConnectionState(
                GatewayConnectionState.Error("[$errorCode] $errorMsg")
            )
            connectionState is GatewayConnectionState.Connecting && reqId == -1 -> {
                emitConnectionState(GatewayConnectionState.Error("[$errorCode] $errorMsg"))
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
        positionRefreshKeysBefore?.let { before ->
            val removed = before - openPositions.keys
            removed.forEach { cancelMarketData(it) }
            positionRefreshKeysBefore = null
        }
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
            IbGatewayLog.debug("Subscribing streaming market data (position) key=$key symbol=${open.symbol}")
            requestPacer.enqueue {
                shareMarketDataSubscription(
                    logicalKey = key,
                    contract = IbContractMapper.forDataRequest(open.contract),
                    normSymbol = open.symbol
                )
            }
        }
    }

    private fun enqueueContractDetails(key: String, open: OpenPosition) {
        if (!client.isConnected || contractDetailsReqIdToKey.containsValue(key)) return
        val reqId = nextContractDetailsReqId.getAndIncrement()
        contractDetailsReqIdToKey[reqId] = key
        client.reqContractDetails(reqId, IbContractMapper.forDataRequest(open.contract))
    }

    private fun shareMarketDataSubscription(
        logicalKey: String,
        contract: Contract,
        normSymbol: String?
    ) {
        if (!client.isConnected || keyToMktDataReqId.containsKey(logicalKey)) return
        val canonical = marketDataCanonicalKey(contract, normSymbol)
        canonicalKeyToReqId[canonical]?.let { existingReqId ->
            attachLogicalMarketDataKey(logicalKey, existingReqId, normSymbol)
            IbGatewayLog.debug(
                "reqMktData reuse reqId=$existingReqId key=$logicalKey canonical=$canonical"
            )
            return
        }
        val reqId = nextMktDataReqId.getAndIncrement()
        canonicalKeyToReqId[canonical] = reqId
        reqIdToCanonicalKey[reqId] = canonical
        attachLogicalMarketDataKey(logicalKey, reqId, normSymbol)
        IbGatewayLog.debug(
            "reqMktData start reqId=$reqId key=$logicalKey canonical=$canonical mode=${connectionMode.name}"
        )
        client.reqMktData(reqId, contract, MARKET_DATA_GENERIC_TICKS, false, false, null)
    }

    private fun attachLogicalMarketDataKey(logicalKey: String, reqId: Int, normSymbol: String?) {
        logicalKeysFor(reqId).add(logicalKey)
        keyToMktDataReqId[logicalKey] = reqId
        normSymbol?.let { streamSymbolByMktDataKey[logicalKey] = it }
        seedMarketDataCachesFromSibling(logicalKey, reqId)
    }

    private fun releaseMarketDataLogicalKey(logicalKey: String) {
        val reqId = keyToMktDataReqId.remove(logicalKey) ?: return
        streamSymbolByMktDataKey.remove(logicalKey)
        marketPrices.remove(logicalKey)
        priorCloses.remove(logicalKey)
        bidPrices.remove(logicalKey)
        askPrices.remove(logicalKey)
        lastTradePrices.remove(logicalKey)
        pendingVolumeDeltaByKey.remove(logicalKey)
        cumulativeVolumeByKey.remove(logicalKey)
        val remaining = logicalKeysFor(reqId)
        remaining.remove(logicalKey)
        if (remaining.isNotEmpty()) return
        mktDataReqIdToLogicalKeys.remove(reqId)
        reqIdToCanonicalKey.remove(reqId)?.let { canonicalKeyToReqId.remove(it) }
        requestPacer.enqueue {
            if (client.isConnected) {
                client.cancelMktData(reqId)
            }
        }
    }

    private fun logicalKeysFor(reqId: Int): MutableSet<String> =
        mktDataReqIdToLogicalKeys.getOrPut(reqId) { ConcurrentHashMap.newKeySet() }

    private fun seedMarketDataCachesFromSibling(logicalKey: String, reqId: Int) {
        val sibling = logicalKeysFor(reqId).firstOrNull { it != logicalKey } ?: return
        marketPrices[sibling]?.let { marketPrices[logicalKey] = it }
        priorCloses[sibling]?.let { priorCloses[logicalKey] = it }
        bidPrices[sibling]?.let { bidPrices[logicalKey] = it }
        askPrices[sibling]?.let { askPrices[logicalKey] = it }
        lastTradePrices[sibling]?.let { lastTradePrices[logicalKey] = it }
    }

    private fun marketDataCanonicalKey(contract: Contract, fallbackSymbol: String? = null): String {
        val conid = contract.conid()
        if (conid > 0) return "MD:conid:$conid"
        val sym = contract.symbol().orEmpty().ifBlank { fallbackSymbol.orEmpty() }.trim().uppercase()
        return "MD:sym:$sym|${contract.getSecType().orEmpty()}|${contract.exchange().orEmpty()}|" +
            "${contract.currency().orEmpty()}|${contract.primaryExch().orEmpty()}"
    }

    private fun applyTickPrice(tickerId: Int, field: Int, price: Double) {
        if (price <= 0.0) return
        val keys = mktDataReqIdToLogicalKeys[tickerId] ?: return
        keys.forEach { key -> applyTickPriceForKey(key, field, price) }
    }

    private fun applyTickSize(tickerId: Int, field: Int, size: Decimal) {
        if (!Decimal.isValid(size)) return
        val keys = mktDataReqIdToLogicalKeys[tickerId] ?: return
        keys.forEach { key -> applyTickSizeForKey(key, field, size) }
    }

    private fun applyTickSizeForKey(key: String, field: Int, size: Decimal) {
        val sizeVal = size.value().toDouble()
        when (field) {
            TickType.LAST_SIZE.index(),
            TickType.DELAYED_LAST_SIZE.index() -> {
                if (sizeVal > 0.0) recordVolumeDelta(key, sizeVal)
            }
            TickType.VOLUME.index(),
            TickType.DELAYED_VOLUME.index() -> {
                val previous = cumulativeVolumeByKey.put(key, sizeVal)
                if (previous != null && sizeVal > previous) {
                    recordVolumeDelta(key, sizeVal - previous)
                }
            }
        }
    }

    private fun applyTickString(tickerId: Int, tickType: Int, value: String) {
        if (tickType != TickType.RT_VOLUME.index()) return
        val tradeSize = IbRtVolumeParser.tradeSizeFromRtVolume(value) ?: return
        val keys = mktDataReqIdToLogicalKeys[tickerId] ?: return
        keys.forEach { key -> recordVolumeDelta(key, tradeSize) }
    }

    private fun recordVolumeDelta(key: String, delta: Double) {
        if (delta <= 0.0) return
        pendingVolumeDeltaByKey.merge(key, delta, Double::plus)
        resolveSymbolForMarketDataKey(key)?.let { symbol -> updateQuoteFor(symbol, key) }
    }

    private fun buildLiveQuote(symbol: String, key: String): LiveQuote {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val tickVolume = pendingVolumeDeltaByKey.remove(key)?.takeIf { it > 0.0 }
        return LiveQuote(
            symbol = norm,
            bid = bidPrices[key],
            ask = askPrices[key],
            last = lastTradePrices[key] ?: marketPrices[key],
            tickVolume = tickVolume
        )
    }

    private fun applyTickPriceForKey(key: String, field: Int, price: Double) {
        var priceUpdated = false

        when (field) {
            TickType.LAST.index(),
            TickType.DELAYED_LAST.index() -> {
                lastTradePrices[key] = price
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
            val symbol = resolveSymbolForMarketDataKey(key)
            IbPriceDiskLog.tick(
                symbol = symbol,
                key = key,
                field = field,
                price = price,
                bid = bidPrices[key],
                ask = askPrices[key],
                last = lastTradePrices[key] ?: marketPrices[key]
            )
            symbol?.let {
                updateQuoteFor(it, key)
                if (field == TickType.BID.index() || field == TickType.DELAYED_BID.index()) {
                    IbGatewayLog.debug("Tick BID symbol=$symbol key=$key price=$price")
                } else if (field == TickType.ASK.index() || field == TickType.DELAYED_ASK.index()) {
                    IbGatewayLog.debug("Tick ASK symbol=$symbol key=$key price=$price")
                } else if (field == TickType.LAST.index() || field == TickType.DELAYED_LAST.index()) {
                    IbGatewayLog.debug("Tick LAST symbol=$symbol key=$key price=$price")
                }
            }
            openPositions[key]?.let { logPositionDiagThrottled(it, "tick") }
            forwardLiveQuoteIfNeeded(key)
            if (!marketDataOnly) {
                publishPositions(immediate = false)
            }
        }
    }

    private fun updateQuoteFor(symbol: String, key: String) {
        val quote = buildLiveQuote(symbol, key)
        quotesBySymbol[quote.symbol] = quote
        val bus = quoteBus
        when {
            bus != null && marketDataOnly -> publishQuoteToBusIfPresent(quote.symbol, quote, priorCloses[key], bus)
            bus == null || !marketDataOnly -> scheduleQuotePublish()
        }
    }

    private fun publishQuoteToBusIfPresent(
        norm: String,
        quote: LiveQuote,
        priorClose: Double?,
        bus: daytrader.marketdata.MarketQuoteBus
    ) {
        if (quote.bid == null && quote.ask == null && quote.last == null && quote.tickVolume == null) return
        bus.publish(norm, quote, priorClose, daytrader.marketdata.QuoteSource.EXTERNAL)
    }

    private fun scheduleQuotePublish() {
        quotesPublishJob?.cancel()
        quotesPublishJob = scope.launch {
            delay(PUBLISH_THROTTLE_MS)
            emit(GatewayEvent.QuotesSnapshot(quotesBySymbol.toMap()))
        }
    }

    private fun forwardLiveQuoteIfNeeded(key: String) {
        if (marketDataOnly && quoteBus != null) return
        val symbol = resolveSymbolForMarketDataKey(key) ?: return
        val bid = bidPrices[key] ?: return
        val ask = askPrices[key] ?: return
        if (bid <= 0.0 || ask <= 0.0) return
        val quote = buildLiveQuote(symbol, key)
        val priorClose = priorCloses[key]
        onLiveQuote?.invoke(symbol, quote, priorClose)
    }

    private fun resolveSymbolForMarketDataKey(key: String): String? =
        openPositions[key]?.symbol
            ?: streamSymbolByMktDataKey[key]
            ?: symbolFromStreamingKey(key)

    /** Parses `STREAM:SYMBOL` or `STREAM:SYMBOL:listing` when the map entry is missing. */
    private fun symbolFromStreamingKey(key: String): String? {
        if (!key.startsWith("STREAM:")) return null
        val body = key.removePrefix("STREAM:")
        return body.substringBefore(':').takeIf { it.isNotBlank() }
    }

    /** Subscribes to IB streaming quotes for a symbol (used by hybrid paper mode for emulator marks). */
    private val streamInstrumentByKey = ConcurrentHashMap<String, daytrader.domain.InstrumentIdentity?>()

    fun currentStreamingMarketDataType(): IbStreamingMarketDataType = streamingMarketDataType

    fun setStreamingMarketDataType(type: IbStreamingMarketDataType) {
        streamingMarketDataType = type
        if (!client.isConnected) return
        requestPacer.enqueue {
            if (!client.isConnected) return@enqueue
            client.reqMarketDataType(type.ibCode)
            IbGatewayLog.debug("reqMarketDataType ${type.name} code=${type.ibCode}")
            refreshActiveStreamingSubscriptions()
        }
    }

    private fun refreshActiveStreamingSubscriptions() {
        val activeRefKeys = streamSubscriptionRefCount.entries
            .filter { it.value > 0 }
            .map { it.key }
        activeRefKeys.forEach { refKey ->
            val instrument = streamInstrumentByKey[refKey]
            val symbol = refKey.substringBefore('|')
            val key = streamingOnlyKey(symbol, instrument)
            if (keyToMktDataReqId.containsKey(key)) {
                releaseMarketDataLogicalKey(key)
            }
        }
        activeRefKeys.forEach { refKey ->
            val instrument = streamInstrumentByKey[refKey]
            val symbol = refKey.substringBefore('|')
            subscribeStreamingMarketData(symbol, instrument)
        }
    }

    fun ensureStreamingMarketData(
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity? = null
    ) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        val refKey = streamRefKey(norm, instrument)
        streamInstrumentByKey[refKey] = instrument
        val firstSubscriber = incrementStreamRefCount(refKey)
        if (!firstSubscriber) {
            IbGatewayLog.debug("ensureStreamingMarketData refcount++ symbol=$norm count=${streamSubscriptionRefCount[refKey]}")
            return
        }
        IbGatewayLog.debug("ensureStreamingMarketData subscribe symbol=$norm connected=${client.isConnected}")
        if (!client.isConnected) {
            pendingStreamSymbols.add(refKey)
            return
        }
        subscribeStreamingMarketData(norm, instrument)
    }

    /**
     * Drops a symbol-only streaming subscription when the last holder releases it
     * (e.g. when no deployment session is running for that symbol).
     */
    fun releaseStreamingMarketData(
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity? = null
    ) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        val refKey = streamRefKey(norm, instrument)
        if (!decrementStreamRefCount(refKey)) {
            IbGatewayLog.debug(
                "releaseStreamingMarketData refcount-- symbol=$norm count=${streamSubscriptionRefCount[refKey]}"
            )
            return
        }
        pendingStreamSymbols.remove(refKey)
        streamInstrumentByKey.remove(refKey)
        IbGatewayLog.debug("releaseStreamingMarketData cancel symbol=$norm")
        cancelMarketData(streamingOnlyKey(norm, instrument))
        quotesBySymbol.remove(norm)
        scheduleQuotePublish()
    }

    private fun streamRefKey(symbol: String, instrument: daytrader.domain.InstrumentIdentity?): String =
        instrument?.dedupeKey()?.let { "$symbol|$it" } ?: symbol

    private fun incrementStreamRefCount(norm: String): Boolean {
        val next = (streamSubscriptionRefCount[norm] ?: 0) + 1
        streamSubscriptionRefCount[norm] = next
        return next == 1
    }

    private fun decrementStreamRefCount(norm: String): Boolean {
        val current = streamSubscriptionRefCount[norm] ?: return false
        if (current <= 1) {
            streamSubscriptionRefCount.remove(norm)
            return true
        }
        streamSubscriptionRefCount[norm] = current - 1
        return false
    }

    /** Re-establishes symbol streaming after connect/reconnect (IB reqIds are reset on disconnect). */
    private fun resubscribeAllStreamingSymbols() {
        val activeKeys = streamSubscriptionRefCount.entries
            .filter { it.value > 0 }
            .map { it.key }
        val refKeys = (pendingStreamSymbols.toList() + activeKeys).distinct()
        pendingStreamSymbols.clear()
        refKeys.forEach { refKey ->
            val instrument = streamInstrumentByKey[refKey]
            val symbol = refKey.substringBefore('|')
            subscribeStreamingMarketData(symbol, instrument)
        }
    }

    private fun subscribeStreamingMarketData(
        norm: String,
        instrument: daytrader.domain.InstrumentIdentity? = null
    ) {
        val key = streamingOnlyKey(norm, instrument)
        if (keyToMktDataReqId.containsKey(key)) return
        val contract = IbContractMapper.contractForSymbol(norm, instrument)
        IbGatewayLog.debug("Subscribing streaming market data (symbol-only) key=$key symbol=$norm mode=${connectionMode.name}")
        requestPacer.enqueue {
            if (!client.isConnected) return@enqueue
            shareMarketDataSubscription(
                logicalKey = key,
                contract = IbContractMapper.forDataRequest(contract),
                normSymbol = norm
            )
        }
        scheduleStreamingHistoricalFallback(key, norm, instrument)
    }

    private fun streamingOnlyKey(symbol: String, instrument: daytrader.domain.InstrumentIdentity? = null): String {
        val listing = instrument?.dedupeKey()
        return if (listing.isNullOrBlank()) "STREAM:$symbol" else "STREAM:$symbol:$listing"
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
        if (connectionState is GatewayConnectionState.Connecting ||
            connectionState is GatewayConnectionState.Connected
        ) {
            return
        }

        enrichmentScheduled = false
        positionsLoadFinished = false
        emitConnectionState(GatewayConnectionState.Connecting)
        IbGatewayLog.connecting(config.endpoint, config.clientId)

        try {
            client.eConnect(config.host, config.port, config.clientId)
            if (!client.isConnected) {
                emitConnectionState(
                    GatewayConnectionState.Error(
                        "eConnect returned but socket is not connected (${config.endpoint})"
                    )
                )
                return
            }

            IbGatewayLog.connected(config.endpoint, config.clientId)
            startReader()
        } catch (e: Exception) {
            emitConnectionState(GatewayConnectionState.Error(e.message ?: "Connect failed"))
            logCallbackFailure("performConnect", e)
        }
    }

    private fun performDisconnect() {
        stopReader()
        if (client.isConnected) {
            cancelAllMarketDataPaced()
            cancelAllContractDetailsPaced()
            cancelAllHistoricalPaced()
            cancelAllTouchTurnHistoricalPaced()
            paced { client.reqAccountUpdates(false, config.accountCode) }
            client.eDisconnect()
        }
        requestPacer.clear()
        touchTurnBracketCoordinator.clearAll { pending, reason ->
            emitTouchTurnBracketFailure(pending, reason)
        }
        executionsRefresh.reset()
        clearPositionState()
        emitConnectionState(GatewayConnectionState.Disconnected)
        IbGatewayLog.disconnected()
    }

    private fun requestPositions() {
        if (!client.isConnected) return
        enrichmentScheduled = false
        positionsLoadFinished = false
        historicalFallbackJob?.cancel()
        historicalFallbackJob = null
        positionRefreshKeysBefore = openPositions.keys.toSet()
        clearMarketDataCachesForKeys(positionRefreshKeysBefore.orEmpty())
        cancelAllContractDetailsPaced()
        cancelAllHistoricalPaced()
        openPositions.clear()
        emit(GatewayEvent.PositionsSnapshot(emptyList()))
        paced {
            if (!client.isConnected) return@paced
            IbGatewayLog.requestingPositions()
            client.reqPositions()
        }
    }

    private fun cancelMarketData(key: String) {
        releaseMarketDataLogicalKey(key)
    }

    private fun cancelAllMarketDataPaced() {
        val reqIds = mktDataReqIdToLogicalKeys.keys.toList()
        mktDataReqIdToLogicalKeys.clear()
        keyToMktDataReqId.clear()
        canonicalKeyToReqId.clear()
        reqIdToCanonicalKey.clear()
        reqIds.forEach { reqId ->
            paced {
                if (client.isConnected) {
                    client.cancelMktData(reqId)
                }
            }
        }
        clearLocalMarketDataCaches()
    }

    private fun clearMarketDataCachesForKeys(keys: Collection<String>) {
        keys.forEach { key ->
            marketPrices.remove(key)
            priorCloses.remove(key)
            bidPrices.remove(key)
            askPrices.remove(key)
            lastTradePrices.remove(key)
        }
    }

    private fun clearLocalMarketDataCaches() {
        marketPrices.clear()
        priorCloses.clear()
        bidPrices.clear()
        askPrices.clear()
        lastTradePrices.clear()
        pendingVolumeDeltaByKey.clear()
        cumulativeVolumeByKey.clear()
        streamSymbolByMktDataKey.clear()
    }

    private fun cancelAllContractDetailsPaced() {
        contractDetailsReqIdToKey.keys.toList().forEach { reqId ->
            contractDetailsReqIdToKey.remove(reqId)
            paced {
                if (client.isConnected) {
                    client.cancelContractData(reqId)
                }
            }
        }
        contractDetailsReqIdToKey.clear()
    }

    private fun cancelAllHistoricalPaced() {
        historicalFallbackJob?.cancel()
        historicalFallbackJob = null
        historicalReqIdToKey.keys.toList().forEach { reqId ->
            historicalReqIdToKey.remove(reqId)
            historicalLastBarClose.remove(reqId)
            paced {
                if (client.isConnected) {
                    client.cancelHistoricalData(reqId)
                }
            }
        }
        historicalPrices.clear()
        historicalPendingKeys.clear()
        historicalLastBarClose.clear()
        lastTickDiagAtMs.clear()
    }

    private fun cancelAllTouchTurnHistoricalPaced() {
        touchTurnGatewayRequestId.keys.toList().forEach { reqId ->
            cancelTouchTurnHistorical(reqId)
        }
        touchTurnHistoricalBars.clear()
        touchTurnHistoricalSymbol.clear()
        touchTurnHistoricalMarketZoneId.clear()
        touchTurnHistoricalAllowMissingToday.clear()
        touchTurnGatewayRequestId.clear()
        cancelAllAdrHistoricalPaced()
    }

    private fun cancelAllAdrHistoricalPaced() {
        adrGatewayRequestId.keys.toList().forEach { reqId ->
            cancelAdrHistorical(reqId)
        }
        adrHistoricalBars.clear()
        adrHistoricalSymbol.clear()
        adrHistoricalMarketZoneId.clear()
        adrGatewayRequestId.clear()
    }

    private fun cancelAdrHistorical(reqId: Int) {
        clearHistoricalRequestTimeout(reqId)
        adrHistoricalBars.remove(reqId)
        adrHistoricalSymbol.remove(reqId)
        adrHistoricalMarketZoneId.remove(reqId)
        adrHistoricalCacheKey.remove(reqId)
        adrReqIdForSignalContextGateway.remove(reqId)?.let { signalGatewayId ->
            pendingTouchTurnSignalContext[signalGatewayId]?.dailyFetchFailed = "Daily bar history request cancelled"
            tryCompletePendingTouchTurnSignalContext(signalGatewayId)
            return
        }
        adrGatewayRequestId.remove(reqId)?.let { gatewayRequestId ->
            deliverAdrReady(
                gatewayRequestId,
                Result.failure(IllegalStateException("ADR request cancelled"))
            )
        }
        paced {
            if (client.isConnected) {
                runCatching { client.cancelHistoricalData(reqId) }
            }
        }
    }

    private fun failAdrHistorical(reqId: Int, message: String) {
        clearHistoricalRequestTimeout(reqId)
        adrHistoricalBars.remove(reqId)
        adrHistoricalSymbol.remove(reqId)
        adrHistoricalCacheKey.remove(reqId)
        adrReqIdForSignalContextGateway.remove(reqId)?.let { signalGatewayId ->
            pendingTouchTurnSignalContext[signalGatewayId]?.dailyFetchFailed = message
            tryCompletePendingTouchTurnSignalContext(signalGatewayId)
            return
        }
        adrGatewayRequestId.remove(reqId)?.let { gatewayRequestId ->
            deliverAdrReady(
                gatewayRequestId,
                Result.failure(IllegalStateException(message))
            )
        }
    }

    private fun completeAdrHistorical(reqId: Int) {
        clearHistoricalRequestTimeout(reqId)
        val signalGatewayId = adrReqIdForSignalContextGateway.remove(reqId)
        val symbol = adrHistoricalSymbol.remove(reqId)
        val marketZoneId = adrHistoricalMarketZoneId.remove(reqId)
            ?: SymbolMarkets.marketZoneIdForSession(symbol.orEmpty(), instrument = null)
        val bars = adrHistoricalBars.remove(reqId).orEmpty().map { it.toTouchTurnOhlcBar(marketZoneId) }
        if (signalGatewayId != null) {
            pendingTouchTurnSignalContext[signalGatewayId]?.dailyBars = bars
            tryCompletePendingTouchTurnSignalContext(signalGatewayId)
            return
        }
        val gatewayRequestId = adrGatewayRequestId.remove(reqId) ?: return
        val cacheKey = adrHistoricalCacheKey.remove(reqId)
        val sessionDay = sessionDayYyyyMmDd(marketZoneId)
        val adrResult = TouchTurnLogic.computeAdr14(bars, excludeSessionDayYyyyMmdd = sessionDay)
        if (cacheKey != null) {
            adrCacheByKey[cacheKey] = CachedAdr(sessionDay, adrResult)
        }
        deliverAdrReady(gatewayRequestId, adrResult)
    }

    private fun deliverFirstCandleReady(gatewayRequestId: Long, result: Result<OhlcBar>) {
        oneShotFirstCandle.remove(gatewayRequestId)?.complete(result)
            ?: emit(GatewayEvent.FirstFifteenMinuteCandleReady(gatewayRequestId, result))
    }

    private fun deliverAdrReady(gatewayRequestId: Long, result: Result<Double>) {
        oneShotAdr.remove(gatewayRequestId)?.complete(result)
            ?: emit(GatewayEvent.FourteenDayAdrReady(gatewayRequestId, result))
    }

    private data class PendingTouchTurnSignalContext(
        val marketZoneId: String,
        val sessionDay: String,
        val allowMissingToday: Boolean,
        val rules: TouchTurnRuleConfig,
        var bars15m: List<OhlcBar>? = null,
        var dailyBars: List<OhlcBar>? = null,
        var dailyFetchFailed: String? = null,
    )

    private suspend fun fetchTouchTurnSignalContextComposite(
        symbol: String,
        instrument: InstrumentIdentity?,
        deploymentMarketZoneId: String? = null,
        allowMissingTodayOpeningBar: Boolean = false,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Result<TouchTurnSignalContext> {
        if (!client.isConnected) {
            return Result.failure(IllegalStateException("Not connected to IB Gateway"))
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Symbol is blank"))
        }
        val deferred = CompletableDeferred<Result<TouchTurnSignalContext>>()
        val gatewayRequestId = nextOneShotGatewayRequestId.getAndIncrement()
        oneShotSignalContext[gatewayRequestId] = deferred
        val reqId = nextTouchTurnHistoricalReqId.getAndIncrement()
        val marketZoneId = SymbolMarkets.marketZoneIdForSession(
            trimmed,
            instrument,
            deploymentMarketZoneId = deploymentMarketZoneId
        )
        touchTurnGatewayRequestId[reqId] = gatewayRequestId
        touchTurnHistoricalSymbol[reqId] = trimmed
        touchTurnHistoricalMarketZoneId[reqId] = marketZoneId
        if (allowMissingTodayOpeningBar) {
            touchTurnHistoricalAllowMissingToday[reqId] = true
        }
        touchTurnHistoricalRules[reqId] = rules
        val sessionDay = sessionDayYyyyMmDd(marketZoneId)
        pendingTouchTurnSignalContext[gatewayRequestId] = PendingTouchTurnSignalContext(
            marketZoneId = marketZoneId,
            sessionDay = sessionDay,
            allowMissingToday = allowMissingTodayOpeningBar,
            rules = rules
        )
        if (rules.enables.requiresDailyHistoricalBootstrap()) {
            requestDailyBarsForSignalContext(gatewayRequestId, trimmed, instrument, marketZoneId)
        }
        val fifteenMinuteDuration = TOUCH_TURN_OPENING_BAR_HISTORY_DURATION
        val contract = IbContractMapper.contractForSymbol(trimmed, instrument)
        scheduleHistoricalRequestTimeout(reqId, TOUCH_TURN_HISTORICAL_TIMEOUT_MS) {
            cancelTouchTurnHistorical(reqId)
            failTouchTurnHistorical(
                reqId,
                "Touch Turn 15-minute history request timed out after ${TOUCH_TURN_HISTORICAL_TIMEOUT_MS / 1000}s"
            )
        }
        requestPacer.enqueue {
            if (!client.isConnected) {
                clearHistoricalRequestTimeout(reqId)
                failTouchTurnHistorical(reqId, "Disconnected before Touch Turn 15-minute history request")
                return@enqueue
            }
            client.reqHistoricalData(
                reqId,
                contract,
                "",
                fifteenMinuteDuration,
                TOUCH_TURN_HISTORICAL_BAR_SIZE,
                HISTORICAL_WHAT_TO_SHOW,
                1,
                1,
                true,
                null
            )
        }
        val timeoutMs = TouchTurnDefaults.SIGNAL_CONTEXT_REQUEST_TIMEOUT_MS
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            abortTouchTurnSignalContextComposite(gatewayRequestId, trimmed, timeoutMs)
        }
    }

    private fun abortTouchTurnSignalContextComposite(
        gatewayRequestId: Long,
        symbol: String,
        timeoutMs: Long
    ): Result<TouchTurnSignalContext> {
        val pending = pendingTouchTurnSignalContext.remove(gatewayRequestId)
        oneShotSignalContext.remove(gatewayRequestId)
        cancelInFlightSignalContextHistoricals(gatewayRequestId)
        val pendingLegs = TouchTurnLogic.describeSignalContextBootstrapPendingLegs(
            bars15mReady = pending?.bars15m != null,
            bars15mCount = pending?.bars15m?.size ?: 0,
            dailyBarsRequired = pending?.rules?.enables?.requiresDailyHistoricalBootstrap() == true,
            dailyBarsReady = pending?.dailyBars != null,
            dailyFetchFailed = pending?.dailyFetchFailed
        )
        IbGatewayLog.signalContextBootstrapTimeout(symbol, pendingLegs, timeoutMs)
        val message =
            "Touch Turn signal context timed out after ${timeoutMs / 1000}s (pending: $pendingLegs)"
        return Result.failure(IllegalStateException(message))
    }

    private fun cancelInFlightSignalContextHistoricals(gatewayRequestId: Long) {
        touchTurnGatewayRequestId.entries
            .filter { it.value == gatewayRequestId }
            .map { it.key }
            .toList()
            .forEach { cancelTouchTurnHistorical(it) }
        adrReqIdForSignalContextGateway.entries
            .filter { it.value == gatewayRequestId }
            .map { it.key }
            .toList()
            .forEach { cancelAdrHistorical(it) }
    }

    private fun deliverSignalContextReady(gatewayRequestId: Long, result: Result<TouchTurnSignalContext>) {
        pendingTouchTurnSignalContext.remove(gatewayRequestId)
        oneShotSignalContext.remove(gatewayRequestId)?.complete(result)
            ?: emit(GatewayEvent.TouchTurnSignalContextReady(gatewayRequestId, result))
    }

    private suspend fun requestDailyBarsForSignalContext(
        gatewayRequestId: Long,
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity?,
        marketZoneId: String
    ) {
        if (!client.isConnected) {
            pendingTouchTurnSignalContext[gatewayRequestId]?.dailyFetchFailed =
                "Not connected to IB Gateway"
            tryCompletePendingTouchTurnSignalContext(gatewayRequestId)
            return
        }
        val trimmed = symbol.trim().uppercase()
        val reqId = nextAdrHistoricalReqId.getAndIncrement()
        adrReqIdForSignalContextGateway[reqId] = gatewayRequestId
        adrHistoricalSymbol[reqId] = trimmed
        adrHistoricalMarketZoneId[reqId] = marketZoneId
        val contract = IbContractMapper.contractForSymbol(trimmed, instrument)
        scheduleHistoricalRequestTimeout(reqId, ADR_HISTORICAL_TIMEOUT_MS) {
            failAdrHistorical(
                reqId,
                "Daily bar history request timed out after ${ADR_HISTORICAL_TIMEOUT_MS / 1000}s"
            )
            paced {
                if (client.isConnected) {
                    runCatching { client.cancelHistoricalData(reqId) }
                }
            }
        }
        requestPacer.enqueue {
            if (!client.isConnected) {
                clearHistoricalRequestTimeout(reqId)
                adrReqIdForSignalContextGateway.remove(reqId)
                pendingTouchTurnSignalContext[gatewayRequestId]?.dailyFetchFailed =
                    "Disconnected before daily bar history request"
                tryCompletePendingTouchTurnSignalContext(gatewayRequestId)
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
    }

    private fun tryCompletePendingTouchTurnSignalContext(gatewayRequestId: Long) {
        val pending = pendingTouchTurnSignalContext[gatewayRequestId] ?: return
        val bars15m = pending.bars15m ?: return
        pending.dailyFetchFailed?.let { message ->
            pendingTouchTurnSignalContext.remove(gatewayRequestId)
            deliverSignalContextReady(gatewayRequestId, Result.failure(IllegalStateException(message)))
            return
        }
        if (pending.rules.enables.requiresDailyHistoricalBootstrap() && pending.dailyBars == null) return
        deliverSignalContextReady(
            gatewayRequestId,
            TouchTurnLogic.deriveTouchTurnSignalContext(
                bars = bars15m,
                marketZoneId = pending.marketZoneId,
                sessionDayYyyyMmdd = pending.sessionDay,
                allowMissingTodayOpeningBar = pending.allowMissingToday,
                rules = pending.rules,
                dailyBars = pending.dailyBars
            )
        )
    }

    private fun deliverTouchTurnHistoricalFailure(gatewayRequestId: Long, message: String) {
        val error = IllegalStateException(message)
        val signalDeferred = oneShotSignalContext.remove(gatewayRequestId)
        if (signalDeferred != null) {
            signalDeferred.complete(Result.failure(error))
            return
        }
        deliverFirstCandleReady(gatewayRequestId, Result.failure(error))
    }

    private fun cancelWorkingOrder(orderId: Int) {
        if (!client.isConnected) return
        val working = openOrdersById[orderId] ?: return
        openOrdersById.remove(orderId)
        paced {
            if (!client.isConnected) return@paced
            client.cancelOrder(orderId, OrderCancel())
        }
        publishOpenOrders()
        IbGatewayLog.sessionOrdersCancelled(working.symbol, listOf(orderId))
    }

    private fun cancelTouchTurnHistorical(reqId: Int) {
        clearHistoricalRequestTimeout(reqId)
        touchTurnHistoricalBars.remove(reqId)
        touchTurnHistoricalSymbol.remove(reqId)
        touchTurnHistoricalMarketZoneId.remove(reqId)
        touchTurnHistoricalAllowMissingToday.remove(reqId)
        touchTurnHistoricalRules.remove(reqId)
        touchTurnGatewayRequestId.remove(reqId)?.let { gatewayRequestId ->
            pendingTouchTurnSignalContext.remove(gatewayRequestId)
            deliverTouchTurnHistoricalFailure(gatewayRequestId, "Historical request cancelled")
        }
        paced {
            if (client.isConnected) {
                runCatching { client.cancelHistoricalData(reqId) }
            }
        }
    }

    private fun failTouchTurnHistorical(reqId: Int, message: String) {
        clearHistoricalRequestTimeout(reqId)
        touchTurnHistoricalBars.remove(reqId)
        touchTurnHistoricalSymbol.remove(reqId)
        touchTurnHistoricalMarketZoneId.remove(reqId)
        touchTurnHistoricalAllowMissingToday.remove(reqId)
        touchTurnHistoricalRules.remove(reqId)
        touchTurnGatewayRequestId.remove(reqId)?.let { gatewayRequestId ->
            deliverTouchTurnHistoricalFailure(gatewayRequestId, message)
        }
    }

    private fun completeTouchTurnHistorical(reqId: Int) {
        clearHistoricalRequestTimeout(reqId)
        val gatewayRequestId = touchTurnGatewayRequestId.remove(reqId) ?: return
        val symbol = touchTurnHistoricalSymbol.remove(reqId)
        val marketZoneId = touchTurnHistoricalMarketZoneId.remove(reqId)
            ?: SymbolMarkets.marketZoneIdForSession(symbol.orEmpty(), instrument = null)
        val allowMissingToday = touchTurnHistoricalAllowMissingToday.remove(reqId) == true
        val rules = touchTurnHistoricalRules.remove(reqId) ?: TouchTurnRuleConfig.DEFAULT
        val bars = touchTurnHistoricalBars.remove(reqId).orEmpty()
        val sessionDay = sessionDayYyyyMmDd(marketZoneId)
        val ohlcBars = bars.map { it.toTouchTurnOhlcBar(marketZoneId) }
        val first = TouchTurnLogic.selectFirstFifteenMinuteBar(ohlcBars, marketZoneId, sessionDay)
        val rawSelectedTime = bars
            .filter { it.high() > 0.0 && it.low() > 0.0 }
            .minByOrNull { barTimeSortKey(it.time()) }
            ?.time()
        TouchTurnCandleLog.ibHistoricalBar(
            symbol = symbol.orEmpty(),
            marketZoneId = marketZoneId,
            sessionDayYyyyMmDd = sessionDay,
            rawBarTime = rawSelectedTime,
            selectedBarTime = first?.time,
            totalBars = bars.size,
            sessionDayBars = ohlcBars.count { TouchTurnLogic.barDayKey(it.time) == sessionDay }
        )
        if (oneShotSignalContext.containsKey(gatewayRequestId)) {
            pendingTouchTurnSignalContext[gatewayRequestId]?.bars15m = ohlcBars
            tryCompletePendingTouchTurnSignalContext(gatewayRequestId)
            return
        }
        if (first == null) {
            val sessionLabel = daytrader.domain.RthMarketSessions.forZoneId(marketZoneId).label
            deliverTouchTurnHistoricalFailure(
                gatewayRequestId,
                "No 15-minute bars returned for $sessionLabel session ($sessionDay)"
            )
            return
        }
        deliverFirstCandleReady(gatewayRequestId, Result.success(first))
    }

    private fun clearPositionState() {
        historicalTimeoutJobs.values.forEach { it.cancel() }
        historicalTimeoutJobs.clear()
        positionRefreshKeysBefore = null
        mktDataReqIdToLogicalKeys.clear()
        keyToMktDataReqId.clear()
        canonicalKeyToReqId.clear()
        reqIdToCanonicalKey.clear()
        contractDetailsReqIdToKey.clear()
        instrumentResolveIbReqToGatewayReq.clear()
        instrumentResolveBatches.values.forEach { it.finishJob?.cancel() }
        instrumentResolveBatches.clear()
        instrumentResolveCompleted.clear()
        historicalReqIdToKey.clear()
        historicalPendingKeys.clear()
        historicalLastBarClose.clear()
        touchTurnHistoricalBars.clear()
        touchTurnHistoricalSymbol.clear()
        touchTurnHistoricalMarketZoneId.clear()
        touchTurnHistoricalAllowMissingToday.clear()
        touchTurnGatewayRequestId.clear()
        adrHistoricalBars.clear()
        adrHistoricalSymbol.clear()
        adrHistoricalMarketZoneId.clear()
        adrHistoricalCacheKey.clear()
        adrGatewayRequestId.clear()
        adrCacheByKey.clear()
        clearLocalMarketDataCaches()
        historicalPrices.clear()
        lastTickDiagAtMs.clear()
        quotesBySymbol.clear()
        quotesPublishJob?.cancel()
        quotesPublishJob = null
        openPositions.clear()
        emit(GatewayEvent.PositionsSnapshot(emptyList()))
        openOrdersById.clear()
        emit(GatewayEvent.OpenOrdersSnapshot(emptyList()))
        fillsByExecId.clear()
        orderParentByOrderId.clear()
        trailAdjustmentOrderIds.clear()
        stopTrailParamsByOrderId.clear()
        emit(GatewayEvent.FillsSnapshot(emptyList()))
        emit(GatewayEvent.QuotesSnapshot(emptyMap()))
        openOrdersLoadFinished = false
        enrichmentScheduled = false
        positionsLoadFinished = false
        nextOrderId.set(0)
        publishDebounceJob?.cancel()
        publishDebounceJob = null
    }

    private fun requestOpenOrders() {
        if (!client.isConnected) return
        openOrdersLoadFinished = false
        openOrdersById.clear()
        emit(GatewayEvent.OpenOrdersSnapshot(emptyList()))
        paced {
            if (!client.isConnected) return@paced
            IbGatewayLog.requestingOpenOrders()
            client.reqAllOpenOrders()
        }
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
        val working = toWorkingOrder(orderId, contract, order, orderState)
        trackOrderParent(orderId, working.parentOrderId)
        openOrdersById[orderId] = working
        publishOpenOrders()
    }

    private fun applyOrderStatus(
        orderId: Int,
        status: String,
        filled: Decimal,
        remaining: Decimal,
        permId: Long,
        parentId: Int
    ) {
        val resolvedParent = parentId.takeIf { it > 0 }
            ?: openOrdersById[orderId]?.parentOrderId?.takeIf { it > 0 }
            ?: 0
        trackOrderParent(orderId, resolvedParent)

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
            remaining = remainingQty,
            permId = permId.takeIf { it > 0 } ?: existing.permId,
            parentOrderId = resolvedParent
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
        if (marketDataOnly) return
        emit(GatewayEvent.OpenOrdersSnapshot(openOrdersById.values.sortedBy { it.orderId }))
    }

    private fun resizeTouchTurnBracket(
        requestId: Long,
        request: daytrader.domain.TouchTurnBracketResizeRequest
    ) {
        val submission = IbTouchTurnBracketPlacer.buildResize(
            config = config,
            plan = request.plan,
            orderIds = request.orderIds
        ) ?: run {
            emit(
                GatewayEvent.TouchTurnBracketResized(
                    requestId = requestId,
                    result = Result.failure(IllegalStateException("bracket_resize_build_failed"))
                )
            )
            return
        }
        paced {
            if (!client.isConnected) return@paced
            client.placeOrder(submission.parentOrderId, submission.contract, submission.parent)
            client.placeOrder(submission.takeProfitOrderId, submission.contract, submission.takeProfit)
            client.placeOrder(submission.stopLossOrderId, submission.contract, submission.stopLoss)
            submission.adjustableStop?.let { adjustable ->
                client.placeOrder(submission.adjustableStopOrderId!!, submission.contract, adjustable)
            }
            IbGatewayLog.touchTurnBracketResized(
                submission.symbol,
                request.plan.quantity,
                submission.parentOrderId
            )
            scheduleExecutionsRefresh()
            emit(
                GatewayEvent.TouchTurnBracketResized(
                    requestId = requestId,
                    result = Result.success(request.plan.quantity)
                )
            )
        }
    }

    private fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) {
        val symbolForAck = SymbolMarkets.normalizeSymbol(plan.symbol)
        val submission = IbTouchTurnBracketPlacer.build(
            client = client,
            config = config,
            plan = plan,
            allocateOrderIds = ::allocateOrderIds
        ) ?: run {
            emitTouchTurnBracketFailure(
                symbol = symbolForAck,
                plan = plan,
                orderIds = emptyList(),
                reason = "bracket_build_failed"
            )
            return
        }
        registerBracketOrderIds(
            parentOrderId = submission.parentOrderId,
            takeProfitOrderId = submission.takeProfitOrderId,
            stopLossOrderId = submission.stopLossOrderId,
            adjustableStopOrderId = submission.adjustableStopOrderId
        )
        plan.orders.firstOrNull { it.role == daytrader.domain.TouchTurnOrderRole.STOP_LOSS }?.let { stopLeg ->
            val trigger = stopLeg.trailTriggerPrice
            val armStop = stopLeg.trailArmStopPrice
            if (trigger != null && armStop != null) {
                stopTrailParamsByOrderId[submission.stopLossOrderId] =
                    StopTrailParams(triggerPrice = trigger, trailAmount = 0.0)
            }
        }
        val onFailure = { pending: IbTouchTurnBracketCoordinator.Pending, reason: String ->
            emitTouchTurnBracketFailure(pending, reason)
        }
        touchTurnBracketCoordinator.begin(plan, submission, onFailure)
        pacedPriority {
            if (!client.isConnected) {
                touchTurnBracketCoordinator.failPending(submission.parentOrderId, "not_connected", onFailure)
                return@pacedPriority
            }
            client.placeOrder(submission.parentOrderId, submission.contract, submission.parent)
            client.placeOrder(submission.takeProfitOrderId, submission.contract, submission.takeProfit)
            client.placeOrder(submission.stopLossOrderId, submission.contract, submission.stopLoss)
            submission.adjustableStop?.let { adjustable ->
                client.placeOrder(submission.adjustableStopOrderId!!, submission.contract, adjustable)
            }
            touchTurnBracketCoordinator.onBracketTransmitted(submission.parentOrderId, onFailure)
        }
    }

    private fun notifyTouchTurnBracketOpenOrder(orderId: Int, isWorking: Boolean) {
        touchTurnBracketCoordinator.onOpenOrder(
            orderId = orderId,
            isWorking = isWorking,
            onSuccess = ::emitTouchTurnBracketSuccess,
            onFailure = ::emitTouchTurnBracketFailure
        )
    }

    private fun notifyTouchTurnBracketOrderStatus(orderId: Int, status: String, remaining: Decimal) {
        val remainingQty = decimalToInt(remaining)
        touchTurnBracketCoordinator.onOrderStatus(
            orderId = orderId,
            status = status,
            remainingQuantity = remainingQty,
            onSuccess = ::emitTouchTurnBracketSuccess,
            onFailure = ::emitTouchTurnBracketFailure
        )
    }

    private fun emitTouchTurnBracketSuccess(pending: IbTouchTurnBracketCoordinator.Pending) {
        val submission = pending.submission
        IbGatewayLog.touchTurnBracketPlaced(
            submission.symbol,
            submission.parentOrderId,
            submission.takeProfitOrderId,
            submission.stopLossOrderId
        )
        scheduleExecutionsRefresh()
        emit(
            GatewayEvent.TouchTurnBracketPlaced(
                TouchTurnBracketAck(
                    symbol = submission.symbol,
                    orderIds = pending.orderIds,
                    result = Result.success(Unit),
                    plan = pending.plan
                )
            )
        )
    }

    private fun emitTouchTurnBracketFailure(
        pending: IbTouchTurnBracketCoordinator.Pending,
        reason: String
    ) {
        emitTouchTurnBracketFailure(
            symbol = pending.submission.symbol,
            plan = pending.plan,
            orderIds = pending.orderIds,
            reason = reason
        )
    }

    private fun emitTouchTurnBracketFailure(
        symbol: String,
        plan: TouchTurnOrderPlan,
        orderIds: List<Int>,
        reason: String
    ) {
        emit(
            GatewayEvent.TouchTurnBracketPlaced(
                TouchTurnBracketAck(
                    symbol = symbol,
                    orderIds = orderIds,
                    result = Result.failure(IllegalStateException(reason)),
                    plan = plan
                )
            )
        )
    }

    private fun cancelOpenOrdersForSymbol(symbol: String) {
        if (!client.isConnected) return
        val toCancel = SymbolMarkets.openOrdersForSymbol(symbol, openOrdersById.values.toList())
        if (toCancel.isEmpty()) return
        val orderCancel = OrderCancel()
        toCancel.forEach { working ->
            openOrdersById.remove(working.orderId)
            paced {
                if (!client.isConnected) return@paced
                client.cancelOrder(working.orderId, orderCancel)
            }
        }
        publishOpenOrders()
        IbGatewayLog.sessionOrdersCancelled(symbol, toCancel.map { it.orderId })
    }

    private fun flattenSymbolForSymbol(symbol: String) {
        cancelOpenOrdersForSymbol(symbol)
        closeOpenPositionForSymbol(symbol)
    }

    private fun closeOpenPositionForSymbol(symbol: String) {
        if (!client.isConnected) return
        val open = openPositions.values.firstOrNull { pos ->
            SymbolMarkets.symbolsMatch(symbol, pos.symbol) && pos.quantity != 0
        } ?: return
        val orderId = allocateOrderIds(1) ?: run {
            IbGatewayLog.sessionPositionCloseSkipped(symbol, "Order id not ready")
            return
        }
        val closeQty = kotlin.math.abs(open.quantity)
        val action = if (open.quantity > 0) "SELL" else "BUY"
        val order = Order()
        order.orderId(orderId)
        order.clientId(config.clientId)
        order.action(action)
        order.orderType("MKT")
        order.totalQuantity(Decimal.get(closeQty.toLong()))
        order.tif(Types.TimeInForce.DAY)
        order.goodTillDate("")
        order.transmit(true)
        if (config.accountCode.isNotBlank()) {
            order.account(config.accountCode)
        }
        val contract = IbContractMapper.forDataRequest(IbContractMapper.clone(open.contract))
        paced {
            if (!client.isConnected) return@paced
            client.placeOrder(orderId, contract, order)
            scheduleExecutionsRefresh()
        }
        IbGatewayLog.sessionPositionClosePlaced(symbol, orderId, action, closeQty)
    }

    private fun registerBracketOrderIds(
        parentOrderId: Int,
        takeProfitOrderId: Int,
        stopLossOrderId: Int,
        adjustableStopOrderId: Int?
    ) {
        orderParentByOrderId[parentOrderId] = 0
        orderParentByOrderId[takeProfitOrderId] = parentOrderId
        orderParentByOrderId[stopLossOrderId] = parentOrderId
        adjustableStopOrderId?.let { adjId ->
            trailAdjustmentOrderIds.add(adjId)
            orderParentByOrderId[adjId] = stopLossOrderId
        }
    }

    private fun trackOrderParent(orderId: Int, parentOrderId: Int) {
        orderParentByOrderId[orderId] = parentOrderId.coerceAtLeast(0)
    }

    private fun resolveParentOrderId(fillOrderId: Int): Int {
        orderParentByOrderId[fillOrderId]?.let { return it }
        openOrdersById[fillOrderId]?.let { order ->
            return if (order.parentOrderId > 0) order.parentOrderId else 0
        }
        return 0
    }

    /**
     * Reserves [count] consecutive IB order ids starting at the returned value.
     * Returns null when disconnected or [nextValidId] has not arrived yet.
     */
    private fun allocateOrderIds(count: Int): Int? {
        while (true) {
            val start = nextOrderId.get()
            if (start <= 0) return null
            val end = start + count
            if (nextOrderId.compareAndSet(start, end)) return start
        }
    }

    private fun paced(action: () -> Unit) = requestPacer.enqueue(action)

    private fun pacedPriority(action: () -> Unit) = requestPacer.enqueuePriority(action)

    private fun scheduleExecutionsRefresh() {
        if (!client.isConnected) return
        executionsRefresh.schedule()
    }

    private fun enqueueRequestExecutions() {
        if (!client.isConnected) return
        val reqId = executionsReqId.incrementAndGet()
        val filter = ExecutionFilter().apply {
            clientId(config.clientId)
            if (config.accountCode.isNotBlank()) {
                acctCode(config.accountCode)
            }
        }
        IbGatewayLog.requestingExecutions(reqId)
        client.reqExecutions(reqId, filter)
    }

    private fun applyExecution(contract: Contract, execution: Execution) {
        val execId = execution.execId().orEmpty()
        if (execId.isBlank()) return
        val symbol = resolveSymbol(contract)
        val qty = decimalToInt(execution.shares())
        if (qty <= 0) return
        val existing = fillsByExecId[execId]
        val fillOrderId = execution.orderId()
        fillsByExecId[execId] = BrokerFill(
            execId = execId,
            orderId = fillOrderId,
            permId = execution.permId(),
            parentOrderId = existing?.parentOrderId ?: resolveParentOrderId(fillOrderId),
            symbol = symbol,
            side = execution.side().orEmpty(),
            quantity = qty,
            price = execution.price(),
            time = execution.time().orEmpty(),
            currency = contract.currency().orEmpty().ifBlank { "USD" },
            commission = existing?.commission,
            realizedPnL = existing?.realizedPnL
        )
        publishFills()
    }

    private fun applyCommissionReport(report: CommissionAndFeesReport) {
        val execId = report.execId().orEmpty()
        if (execId.isBlank()) return
        val existing = fillsByExecId[execId] ?: return
        fillsByExecId[execId] = existing.copy(
            commission = report.commissionAndFees(),
            realizedPnL = report.realizedPNL(),
            currency = report.currency().orEmpty().ifBlank { existing.currency }
        )
        publishFills()
    }

    private fun publishFills() {
        if (marketDataOnly) return
        emit(
            GatewayEvent.FillsSnapshot(
                fillsByExecId.values.sortedWith(
                    compareBy<BrokerFill> { it.time }.thenBy { it.execId }
                )
            )
        )
    }

    private fun toWorkingOrder(
        orderId: Int,
        contract: Contract,
        order: Order,
        orderState: OrderState
    ): WorkingOrder {
        val total = decimalToInt(order.totalQuantity())
        val filled = decimalToInt(order.filledQuantity())
        val orderType = order.getOrderType().orEmpty()
        val limit = order.lmtPrice().takeIf { it > 0 }
        val stop = resolveStopPrice(order, orderType)
        val isTrailAdjustment = trailAdjustmentOrderIds.contains(orderId)
        val trailParams = stopTrailParamsByOrderId[orderId]
        val existing = openOrdersById[orderId]
        return WorkingOrder(
            orderId = orderId,
            permId = order.permId(),
            parentOrderId = order.parentId(),
            symbol = resolveSymbol(contract),
            action = order.getAction().orEmpty(),
            quantity = total,
            filled = filled,
            remaining = (total - filled).coerceAtLeast(0),
            orderType = orderType,
            limitPrice = limit,
            stopPrice = stop,
            status = orderStatusLabel(orderState),
            currency = contract.currency().orEmpty().ifBlank { "USD" },
            isTrailAdjustment = isTrailAdjustment,
            trailTriggerPrice = trailParams?.triggerPrice ?: existing?.trailTriggerPrice,
            trailAmount = trailParams?.trailAmount ?: existing?.trailAmount
        )
    }

    private fun resolveStopPrice(order: Order, orderType: String): Double? {
        if (orderType.equals("TRAIL", ignoreCase = true)) {
            return order.trailStopPrice().takeIf { it > 0 }
                ?: order.auxPrice().takeIf { it > 0 }
        }
        return order.auxPrice().takeIf { it > 0 }
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

    private fun isWorkingOpenOrder(status: String, order: Order): Boolean {
        if (isTerminalOrderStatus(status)) return false
        val remaining = remainingQuantity(order)
        if (remaining > 0) return true
        return IbTouchTurnBracketCoordinator.isAcknowledgementStatus(status, remaining)
    }

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
        if (marketDataOnly) return
        val published = openPositions.values
            .map { open ->
                val broker = toAccountPosition(open)
                if (IbGatewayLog.isPositionDiagEnabled()) {
                    logPositionDiag(open, "publish_blotter", broker)
                }
                broker
            }
            .sortedBy { it.symbol }

        emit(GatewayEvent.PositionsSnapshot(published))
    }

    private fun scheduleHistoricalFallbackPass() {
        historicalFallbackJob?.cancel()
        historicalFallbackJob = scope.launch {
            delay(HISTORICAL_FALLBACK_DELAY_MS)
            if (!client.isConnected) return@launch
            val pending = openPositions.values.filter { needsHistoricalFallback(it, it.key) }
            pending.chunked(IbRateLimits.HISTORICAL_FALLBACK_BATCH_SIZE).forEach { batch ->
                if (!client.isConnected) return@launch
                batch.forEach { open ->
                    requestPacer.enqueue {
                        enqueueHistoricalClose(open.key, open)
                    }
                }
                if (batch.size == IbRateLimits.HISTORICAL_FALLBACK_BATCH_SIZE) {
                    delay(HISTORICAL_FALLBACK_BATCH_DELAY_MS)
                }
            }
        }
    }

    private fun scheduleHistoricalRequestTimeout(reqId: Int, timeoutMs: Long, onTimeout: () -> Unit) {
        historicalTimeoutJobs[reqId]?.cancel()
        historicalTimeoutJobs[reqId] = scope.launch {
            delay(timeoutMs)
            if (historicalTimeoutJobs.remove(reqId) != null) {
                onTimeout()
            }
        }
    }

    private fun clearHistoricalRequestTimeout(reqId: Int) {
        historicalTimeoutJobs.remove(reqId)?.cancel()
    }

    private fun adrCacheKey(symbol: String, instrument: daytrader.domain.InstrumentIdentity?): String {
        val listing = instrument?.dedupeKey()
        return if (listing.isNullOrBlank()) symbol else "$symbol|$listing"
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
        val close = historicalLastBarClose.remove(reqId)
        if (key.startsWith(WATCHLIST_HISTORICAL_KEY_PREFIX)) {
            val gatewayRequestId = key.removePrefix(WATCHLIST_HISTORICAL_KEY_PREFIX).toLongOrNull()
            if (gatewayRequestId != null) {
                val result = close?.takeIf { it > 0.0 }
                    ?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("No historical close received for watchlist scan"))
                deliverLatestDailyCloseReady(gatewayRequestId, result)
            }
            return
        }
        if (close == null || close <= 0.0) return
        historicalPrices[key] = close
        if (key.startsWith("STREAM:")) {
            applyStreamingHistoricalClose(key, close)
            return
        }
        openPositions[key]?.let { open ->
            IbGatewayLog.historicalCloseApplied(open.contract, close)
            logPositionDiag(open, "historical")
        }
        publishPositions(immediate = true)
    }

    private fun requestLatestDailyClose(
        gatewayRequestId: Long,
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        if (!client.isConnected) {
            deliverLatestDailyCloseReady(
                gatewayRequestId,
                Result.failure(IllegalStateException("Not connected to IB Gateway"))
            )
            return
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            deliverLatestDailyCloseReady(
                gatewayRequestId,
                Result.failure(IllegalArgumentException("Symbol is blank"))
            )
            return
        }
        val key = "$WATCHLIST_HISTORICAL_KEY_PREFIX$gatewayRequestId"
        if (!historicalPendingKeys.add(key)) {
            deliverLatestDailyCloseReady(
                gatewayRequestId,
                Result.failure(IllegalStateException("Watchlist price request already pending for $trimmed"))
            )
            return
        }
        val reqId = nextHistoricalReqId.getAndIncrement()
        historicalReqIdToKey[reqId] = key
        scheduleHistoricalRequestTimeout(reqId, QueuedBrokerGateway.HISTORICAL_REQUEST_TIMEOUT_MS) {
            historicalReqIdToKey.remove(reqId)
            historicalLastBarClose.remove(reqId)
            historicalPendingKeys.remove(key)
            deliverLatestDailyCloseReady(
                gatewayRequestId,
                Result.failure(IllegalStateException("Watchlist price request timed out"))
            )
        }
        requestPacer.enqueue {
            if (!client.isConnected) {
                clearHistoricalRequestTimeout(reqId)
                historicalReqIdToKey.remove(reqId)
                historicalPendingKeys.remove(key)
                deliverLatestDailyCloseReady(
                    gatewayRequestId,
                    Result.failure(IllegalStateException("Disconnected before watchlist price request"))
                )
                return@enqueue
            }
            val contract = IbContractMapper.forDataRequest(
                IbContractMapper.contractForSymbol(trimmed, instrument)
            )
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
    }

    private fun deliverLatestDailyCloseReady(gatewayRequestId: Long, result: Result<Double>) {
        emit(GatewayEvent.LatestDailyCloseReady(gatewayRequestId, result))
    }

    private fun applyStreamingHistoricalClose(key: String, close: Double) {
        val symbol = resolveSymbolForMarketDataKey(key) ?: return
        marketPrices[key] = close
        lastTradePrices[key] = close
        priorCloses[key] = close
        val spread = close * 0.0001
        if (bidPrices[key] == null) bidPrices[key] = close - spread
        if (askPrices[key] == null) askPrices[key] = close + spread
        IbGatewayLog.debug("Streaming historical close symbol=$symbol key=$key close=$close")
        updateQuoteFor(symbol, key)
        forwardLiveQuoteIfNeeded(key)
    }

    private fun streamingKeyHasQuote(key: String): Boolean {
        val bid = bidPrices[key]
        val ask = askPrices[key]
        if (bid != null && bid > 0.0 && ask != null && ask > 0.0) return true
        val last = lastTradePrices[key] ?: marketPrices[key]
        return last != null && last > 0.0
    }

    private fun scheduleStreamingHistoricalFallback(
        key: String,
        norm: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        if (!marketDataOnly) return
        scope.launch {
            delay(HISTORICAL_FALLBACK_DELAY_MS)
            if (!client.isConnected) return@launch
            if (keyToMktDataReqId[key] == null) return@launch
            if (streamingKeyHasQuote(key)) return@launch
            requestPacer.enqueue {
                enqueueStreamingHistoricalClose(key, norm, instrument)
            }
        }
    }

    private fun enqueueStreamingHistoricalClose(
        key: String,
        norm: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        if (!client.isConnected || !historicalPendingKeys.add(key)) return
        val reqId = nextHistoricalReqId.getAndIncrement()
        historicalReqIdToKey[reqId] = key
        val contract = IbContractMapper.forDataRequest(
            IbContractMapper.contractForSymbol(norm, instrument)
        )
        IbGatewayLog.debug("Streaming historical fallback reqId=$reqId key=$key symbol=$norm")
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
        blotter: AccountPosition? = null
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
        blotter: AccountPosition? = null
    ): PositionDiagSnapshot {
        val key = open.key
        val magnifier = open.priceMagnifier
        val contract = open.contract
        val portfolioMkt = open.portfolioMarketPrice
        val tickMkt = lastTradePrices[key] ?: marketPrices[key]
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
        val published = blotter ?: toAccountPosition(open)
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

    private fun toAccountPosition(open: OpenPosition): AccountPosition {
        val key = open.key
        val contractMagnifier = open.priceMagnifier
        val (marketRaw, _) = resolveMarketRawAndSource(open, key)
        val avgRaw = open.portfolioAverageCost ?: open.avgCostRaw
        val magnifiers = IbPriceScale.resolvePriceMagnifiers(avgRaw, marketRaw, contractMagnifier)
        val avgMajor = IbPriceScale.toMajorCurrency(avgRaw, magnifiers.avgMagnifier)
        val marketMajor = IbPriceScale.toMajorCurrency(marketRaw, magnifiers.marketMagnifier)
        val bidMajor = bidPrices[key]?.let { IbPriceScale.toMajorCurrency(it, magnifiers.marketMagnifier) }
        val askMajor = askPrices[key]?.let { IbPriceScale.toMajorCurrency(it, magnifiers.marketMagnifier) }
        val lastMajor = lastTradePrices[key]?.let { IbPriceScale.toMajorCurrency(it, magnifiers.marketMagnifier) }
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
        return AccountPosition(
            account = open.account,
            symbol = open.symbol,
            companyName = open.companyName,
            quantity = open.quantity,
            avgPrice = avgMajor,
            marketPrice = marketMajor,
            bidPrice = bidMajor,
            askPrice = askMajor,
            lastTradePrice = lastMajor,
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

    private data class CachedAdr(val sessionDay: String, val result: Result<Double>)

    private data class InstrumentResolveBatch(
        val gatewayRequestId: Long,
        val symbol: String,
        val pendingIbReqIds: MutableSet<Int> = mutableSetOf(),
        val candidates: LinkedHashMap<String, daytrader.domain.ResolvedInstrument> = linkedMapOf(),
        var companyName: String? = null,
        var finishJob: Job? = null
    )

    private companion object {
        const val MKT_DATA_REQ_ID_START = 10_000
        const val CONTRACT_DETAILS_REQ_ID_START = 20_000
        const val INSTRUMENT_RESOLVE_REQ_ID_START = 25_000
        const val INSTRUMENT_RESOLVE_DEBOUNCE_MS = 400L
        const val INSTRUMENT_RESOLVE_TIMEOUT_MS = 12_000L
        const val HISTORICAL_REQ_ID_START = 30_000
        const val TOUCH_TURN_HISTORICAL_REQ_ID_START = 50_000
        /** See [TouchTurnDefaults.TOUCH_TURN_15M_HISTORY_DURATION]. */
        val TOUCH_TURN_HISTORICAL_DURATION: String = TouchTurnDefaults.TOUCH_TURN_15M_HISTORY_DURATION
        val TOUCH_TURN_OPENING_BAR_HISTORY_DURATION: String =
            TouchTurnDefaults.TOUCH_TURN_OPENING_BAR_HISTORY_DURATION
        const val TOUCH_TURN_HISTORICAL_BAR_SIZE = "15 mins"
        const val TOUCH_TURN_HISTORICAL_TIMEOUT_MS = 45_000L
        /** RT Volume generic tick for live trade-size updates (volume buffer). */
        const val MARKET_DATA_GENERIC_TICKS = "233"
        const val ADR_HISTORICAL_REQ_ID_START = 55_000
        const val REVERSAL_SCORE_MKT_DATA_REQ_ID_START = 70_000
        const val ADR_HISTORICAL_DURATION = "20 D"
        const val ADR_HISTORICAL_BAR_SIZE = "1 day"
        const val ADR_HISTORICAL_TIMEOUT_MS = 45_000L
        const val PUBLISH_THROTTLE_MS = 300L
        const val HISTORICAL_FALLBACK_DELAY_MS = 4_000L
        const val HISTORICAL_FALLBACK_BATCH_DELAY_MS = 2_000L
        const val HISTORICAL_DURATION = "2 D"
        const val HISTORICAL_BAR_SIZE = "1 day"
        const val HISTORICAL_WHAT_TO_SHOW = "TRADES"
        const val WATCHLIST_HISTORICAL_KEY_PREFIX = "WATCHLIST:"
        const val TICK_DIAG_MIN_INTERVAL_MS = 5_000L
        /** Delayed data; when the market is closed, shows the last delayed quote (not zero). */
        const val MARKET_DATA_TYPE_DELAYED_FROZEN = 4
        /** Real-time quotes (hybrid paper mode). Falls back per IB subscription. */
        const val MARKET_DATA_TYPE_LIVE = 1

        val INFO_ERROR_CODES = setOf(
            2104, 2106, 2107, 2158,
            2110, // TWS ↔ IB server link down; restores automatically
            2119, // Market data farm is connecting
            10167 // Requested market data not subscribed; displaying delayed data
        )
        /** Cancelled or unknown historical ticker — safe to ignore after [cancelHistoricalData]. */
        val HISTORICAL_BENIGN_ERROR_CODES = setOf(366)
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

        fun sessionDayYyyyMmDd(marketZoneId: String): String =
            TouchTurnLogic.sessionDayYyyyMmDd(marketZoneId)
    }
}

private fun Bar.toTouchTurnOhlcBar(marketZoneId: String): OhlcBar {
    val barVolume = volume()
    val volume = if (Decimal.isValid(barVolume)) barVolume.value().toDouble() else 0.0
    return OhlcBar(
        open = open(),
        high = high(),
        low = low(),
        close = close(),
        time = TouchTurnLogic.normalizeIbBarTimeToMarketZone(time(), marketZoneId),
        volume = volume
    )
}
