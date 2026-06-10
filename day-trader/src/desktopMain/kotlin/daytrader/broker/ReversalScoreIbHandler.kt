package daytrader.broker

import com.ib.client.Bar
import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.TickType
import com.ib.client.Types
import com.ib.client.protobuf.HistoricalDataProto
import daytrader.domain.HomeMarketMacroBenchmark
import daytrader.domain.InstrumentIdentity
import daytrader.domain.MacroBenchmark
import daytrader.domain.MacroRegimeEvaluator
import daytrader.domain.MacroRegimeSnapshot
import daytrader.domain.ReversalScoreHistoricalSnapshot
import daytrader.domain.ReversalScoreLiveSnapshot
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.SpyRegimeSnapshot
import daytrader.domain.SpyRegimeEvaluator
import daytrader.diagnostics.ReversalScoreLog
import daytrader.gateway.GatewayEvent
import daytrader.gateway.QueuedBrokerGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * IB TWS requests for reversal score symbol and macro volatility inputs.
 */
internal class ReversalScoreIbHandler(
    private val scope: CoroutineScope,
    private val requestPacer: IbRequestPacer,
    private val clientProvider: () -> EClientSocket,
    private val isConnected: () -> Boolean,
    private val nextHistoricalReqId: () -> Int,
    private val nextMktDataReqId: () -> Int,
    private val emit: (GatewayEvent) -> Unit
) {
    private enum class SymbolStage { DAILY, IV, LIVE }
    private enum class MacroIndex { VIX, VIX1D, VVIX }

    private data class SymbolRequest(
        val gatewayRequestId: Long,
        val symbol: String,
        val instrument: InstrumentIdentity?,
        var stage: SymbolStage = SymbolStage.DAILY,
        val dailyBars: MutableList<Bar> = mutableListOf(),
        val ivBars: MutableList<Bar> = mutableListOf(),
        var live: LiveAccumulator = LiveAccumulator()
    )

    private data class LiveAccumulator(
        var lastPrice: Double? = null,
        var volume: Double? = null,
        var impliedVolatility: Double? = null
    )

    private data class MacroRequest(
        val gatewayRequestId: Long,
        val liveValues: MutableMap<MacroIndex, Double> = ConcurrentHashMap(),
        val histories: MutableMap<MacroIndex, MutableList<Double>> = ConcurrentHashMap(),
        var pendingLive: MutableSet<MacroIndex> = MacroIndex.entries.toMutableSet(),
        var pendingHistory: MutableSet<MacroIndex> = MacroIndex.entries.toMutableSet()
    )

    private val symbolByHistoricalReqId = ConcurrentHashMap<Int, SymbolRequest>()
    private val symbolByLiveReqId = ConcurrentHashMap<Int, Long>()
    private val symbolRequests = ConcurrentHashMap<Long, SymbolRequest>()
    private val symbolTimeoutJobs = ConcurrentHashMap<Long, Job>()

    private val macroByHistoricalReqId = ConcurrentHashMap<Int, Pair<Long, MacroIndex>>()
    private val macroByLiveReqId = ConcurrentHashMap<Int, Pair<Long, MacroIndex>>()
    private val macroRequests = ConcurrentHashMap<Long, MacroRequest>()
    private val macroTimeoutJobs = ConcurrentHashMap<Long, Job>()

    private data class SpyRegimeRequest(
        val gatewayRequestId: Long,
        val dailyCloses: MutableList<Double> = mutableListOf(),
        var liveLast: Double? = null
    )

    private val spyRequests = ConcurrentHashMap<Long, SpyRegimeRequest>()
    private val spyByHistoricalReqId = ConcurrentHashMap<Int, Long>()
    private val spyByLiveReqId = ConcurrentHashMap<Int, Long>()
    private val spyTimeoutJobs = ConcurrentHashMap<Long, Job>()

    private data class HomeMarketRegimeRequest(
        val gatewayRequestId: Long,
        val benchmark: MacroBenchmark,
        val contractCandidates: List<Contract>,
        var contractAttempt: Int = 0,
        val dailyCloses: MutableList<Double> = mutableListOf(),
        var liveLast: Double? = null
    )

    private val homeMarketRegimeRequests = ConcurrentHashMap<Long, HomeMarketRegimeRequest>()
    private val homeMarketRegimeByHistoricalReqId = ConcurrentHashMap<Int, Long>()
    private val homeMarketRegimeByLiveReqId = ConcurrentHashMap<Int, Long>()
    private val homeMarketRegimeTimeoutJobs = ConcurrentHashMap<Long, Job>()

    fun requestSymbolSnapshot(gatewayRequestId: Long, symbol: String, instrument: InstrumentIdentity?) {
        if (!isConnected()) {
            val error = IllegalStateException("Not connected to IB Gateway")
            ReversalScoreLog.ibSymbolFailed(symbol, gatewayRequestId, error)
            deliverSymbol(gatewayRequestId, Result.failure(error))
            return
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            val error = IllegalArgumentException("Symbol is blank")
            ReversalScoreLog.ibSymbolFailed(trimmed, gatewayRequestId, error)
            deliverSymbol(gatewayRequestId, Result.failure(error))
            return
        }
        ReversalScoreLog.ibSymbolStage(trimmed, gatewayRequestId, "started", "instrument=${instrument?.dedupeKey() ?: "none"}")
        val request = SymbolRequest(gatewayRequestId, trimmed, instrument)
        symbolRequests[gatewayRequestId] = request
        scheduleSymbolTimeout(gatewayRequestId)
        requestPacer.enqueue {
            if (!isConnected()) {
                failSymbol(gatewayRequestId, IllegalStateException("Disconnected before reversal score request"))
                return@enqueue
            }
            ReversalScoreLog.ibSymbolStage(trimmed, gatewayRequestId, "daily_historical_enqueued")
            val reqId = nextHistoricalReqId()
            symbolByHistoricalReqId[reqId] = request
            val contract = IbContractMapper.forDataRequest(IbContractMapper.contractForSymbol(trimmed, instrument))
            clientProvider().reqHistoricalData(
                reqId,
                contract,
                "",
                SYMBOL_DAILY_DURATION,
                SYMBOL_DAILY_BAR_SIZE,
                SYMBOL_DAILY_WHAT_TO_SHOW,
                1,
                1,
                false,
                null
            )
        }
    }

    fun requestMacroVolatility(gatewayRequestId: Long) {
        if (!isConnected()) {
            val error = IllegalStateException("Not connected to IB Gateway")
            ReversalScoreLog.ibMacroFailed(gatewayRequestId, error)
            deliverMacro(gatewayRequestId, Result.failure(error))
            return
        }
        ReversalScoreLog.ibMacroStage(gatewayRequestId, "started")
        macroRequests[gatewayRequestId] = MacroRequest(gatewayRequestId)
        scheduleMacroTimeout(gatewayRequestId)
        MacroIndex.entries.forEach { index ->
            requestPacer.enqueue {
                if (!isConnected()) return@enqueue
                val historyReqId = nextHistoricalReqId()
                macroByHistoricalReqId[historyReqId] = gatewayRequestId to index
                clientProvider().reqHistoricalData(
                    historyReqId,
                    macroContract(index),
                    "",
                    MACRO_HISTORY_DURATION,
                    SYMBOL_DAILY_BAR_SIZE,
                    SYMBOL_DAILY_WHAT_TO_SHOW,
                    1,
                    1,
                    false,
                    null
                )
                val liveReqId = nextMktDataReqId()
                macroByLiveReqId[liveReqId] = gatewayRequestId to index
                clientProvider().reqMktData(liveReqId, macroContract(index), "", true, false, null)
                scheduleMacroLiveFallback(gatewayRequestId, index, liveReqId)
            }
        }
    }

    private fun scheduleMacroLiveFallback(gatewayRequestId: Long, index: MacroIndex, liveReqId: Int) {
        scope.launch {
            delay(SNAPSHOT_FALLBACK_MS)
            if (macroByLiveReqId.remove(liveReqId) == null) return@launch
            clientProvider().cancelMktData(liveReqId)
            completeMacroLiveIndex(gatewayRequestId, index, reason = "snapshot_fallback")
        }
    }

    private fun completeMacroLiveIndex(gatewayRequestId: Long, index: MacroIndex, reason: String) {
        val request = macroRequests[gatewayRequestId] ?: return
        if (index !in request.pendingLive) return
        if (!request.liveValues.containsKey(index)) {
            request.histories[index]?.lastOrNull()?.let { request.liveValues[index] = it }
        }
        request.pendingLive.remove(index)
        ReversalScoreLog.ibMacroStage(
            gatewayRequestId,
            "live_complete",
            "index=$index reason=$reason value=${request.liveValues[index]}"
        )
        maybeCompleteMacro(gatewayRequestId)
    }

    fun onIbError(reqId: Int, errorCode: Int, errorMsg: String): Boolean {
        homeMarketRegimeByHistoricalReqId.remove(reqId)?.let { gatewayRequestId ->
            val request = homeMarketRegimeRequests[gatewayRequestId]
            ReversalScoreLog.homeMarketRegimeStage(
                benchmarkSymbol = request?.benchmark?.symbol ?: "unknown",
                gatewayRequestId = gatewayRequestId,
                stage = "history_error",
                detail = "code=$errorCode msg=$errorMsg attempt=${request?.contractAttempt ?: -1}"
            )
            if (!advanceHomeMarketRegimeHistorical(gatewayRequestId, "history_error_$errorCode")) {
                failHomeMarketRegime(
                    gatewayRequestId,
                    IllegalStateException(
                        "${request?.benchmark?.label ?: "Home market"} historical error $errorCode: $errorMsg"
                    )
                )
            }
            return true
        }
        homeMarketRegimeByLiveReqId.remove(reqId)?.let { gatewayRequestId ->
            ReversalScoreLog.homeMarketRegimeStage(
                benchmarkSymbol = homeMarketRegimeRequests[gatewayRequestId]?.benchmark?.symbol ?: "unknown",
                gatewayRequestId = gatewayRequestId,
                stage = "live_error",
                detail = "code=$errorCode msg=$errorMsg"
            )
            completeHomeMarketRegime(gatewayRequestId)
            return true
        }
        spyByHistoricalReqId.remove(reqId)?.let { gatewayRequestId ->
            failSpyRegime(gatewayRequestId, IllegalStateException("SPY historical error $errorCode: $errorMsg"))
            return true
        }
        spyByLiveReqId.remove(reqId)?.let { gatewayRequestId ->
            completeSpyRegime(gatewayRequestId)
            return true
        }
        macroByHistoricalReqId.remove(reqId)?.let { (gatewayRequestId, index) ->
            ReversalScoreLog.ibMacroStage(
                gatewayRequestId,
                "history_error",
                "index=$index code=$errorCode msg=$errorMsg"
            )
            macroRequests[gatewayRequestId]?.pendingHistory?.remove(index)
            maybeCompleteMacro(gatewayRequestId)
            return true
        }
        macroByLiveReqId.remove(reqId)?.let { (gatewayRequestId, index) ->
            ReversalScoreLog.ibMacroStage(
                gatewayRequestId,
                "live_error",
                "index=$index code=$errorCode msg=$errorMsg"
            )
            completeMacroLiveIndex(gatewayRequestId, index, reason = "error_$errorCode")
            return true
        }
        return false
    }

    fun onHistoricalData(reqId: Int, bar: Bar): Boolean {
        if (bar.close() <= 0.0) return tracksHistoricalReqId(reqId)
        recordHistoricalClose(reqId, bar.close())
        return tracksHistoricalReqId(reqId)
    }

    fun onHistoricalDataProtoBuf(reqId: Int, historicalData: HistoricalDataProto.HistoricalData): Boolean {
        if (!tracksHistoricalReqId(reqId)) return false
        for (index in 0 until historicalData.historicalDataBarsCount) {
            val bar = historicalData.getHistoricalDataBars(index)
            if (bar.hasClose() && bar.close > 0.0) {
                recordHistoricalClose(reqId, bar.close)
            }
        }
        return true
    }

    private fun tracksHistoricalReqId(reqId: Int): Boolean =
        symbolByHistoricalReqId.containsKey(reqId) ||
            macroByHistoricalReqId.containsKey(reqId) ||
            spyByHistoricalReqId.containsKey(reqId) ||
            homeMarketRegimeByHistoricalReqId.containsKey(reqId)

    private fun hasSufficientHomeMarketDailyCloses(count: Int): Boolean =
        count >= MacroRegimeEvaluator.SMA_WINDOW

    private fun recordHistoricalClose(reqId: Int, close: Double) {
        homeMarketRegimeByHistoricalReqId[reqId]?.let { gatewayRequestId ->
            homeMarketRegimeRequests[gatewayRequestId]?.dailyCloses?.add(close)
            return
        }
        spyByHistoricalReqId[reqId]?.let { gatewayRequestId ->
            spyRequests[gatewayRequestId]?.dailyCloses?.add(close)
            return
        }
        symbolByHistoricalReqId[reqId]?.let { request ->
            when (request.stage) {
                SymbolStage.DAILY -> request.dailyBars.add(barWithClose(close))
                SymbolStage.IV -> request.ivBars.add(barWithClose(close))
                SymbolStage.LIVE -> Unit
            }
            return
        }
        macroByHistoricalReqId[reqId]?.let { (gatewayRequestId, index) ->
            macroRequests[gatewayRequestId]
                ?.histories
                ?.getOrPut(index) { mutableListOf() }
                ?.add(close)
        }
    }

    private fun barWithClose(close: Double): Bar =
        Bar("", close, close, close, close, Decimal.parse("0"), 0, Decimal.parse(close.toString()))

    fun onHistoricalDataEnd(reqId: Int): Boolean {
        symbolByHistoricalReqId.remove(reqId)?.let { request ->
            when (request.stage) {
                SymbolStage.DAILY -> {
                    if (request.dailyBars.isEmpty()) {
                        failSymbol(request.gatewayRequestId, IllegalStateException("No daily bars for reversal score"))
                        return true
                    }
                    ReversalScoreLog.ibSymbolStage(
                        request.symbol,
                        request.gatewayRequestId,
                        "daily_historical_complete",
                        "barCount=${request.dailyBars.size}"
                    )
                    request.stage = SymbolStage.IV
                    requestPacer.enqueue {
                        val ivReqId = nextHistoricalReqId()
                        symbolByHistoricalReqId[ivReqId] = request
                        val contract = IbContractMapper.forDataRequest(
                            IbContractMapper.contractForSymbol(request.symbol, request.instrument)
                        )
                        clientProvider().reqHistoricalData(
                            ivReqId,
                            contract,
                            "",
                            SYMBOL_IV_DURATION,
                            SYMBOL_DAILY_BAR_SIZE,
                            SYMBOL_IV_WHAT_TO_SHOW,
                            1,
                            1,
                            false,
                            null
                        )
                    }
                }
                SymbolStage.IV -> {
                    ReversalScoreLog.ibSymbolStage(
                        request.symbol,
                        request.gatewayRequestId,
                        "iv_historical_complete",
                        "ivBarCount=${request.ivBars.size}"
                    )
                    request.stage = SymbolStage.LIVE
                    requestPacer.enqueue {
                        val liveReqId = nextMktDataReqId()
                        symbolByLiveReqId[liveReqId] = request.gatewayRequestId
                        val contract = IbContractMapper.forDataRequest(
                            IbContractMapper.contractForSymbol(request.symbol, request.instrument)
                        )
                        clientProvider().reqMktData(
                            liveReqId,
                            contract,
                            REVERSAL_SCORE_GENERIC_TICKS,
                            true,
                            false,
                            null
                        )
                        scope.launch {
                            delay(SNAPSHOT_FALLBACK_MS)
                            if (symbolByLiveReqId.remove(liveReqId) != null) {
                                clientProvider().cancelMktData(liveReqId)
                                completeSymbol(request.gatewayRequestId)
                            }
                        }
                    }
                }
                SymbolStage.LIVE -> Unit
            }
            return true
        }
        macroByHistoricalReqId.remove(reqId)?.let { (gatewayRequestId, index) ->
            ReversalScoreLog.ibMacroStage(
                gatewayRequestId,
                "history_complete",
                "index=$index historyCount=${macroRequests[gatewayRequestId]?.histories?.get(index)?.size ?: 0}"
            )
            macroRequests[gatewayRequestId]?.pendingHistory?.remove(index)
            maybeCompleteMacro(gatewayRequestId)
            return true
        }
        homeMarketRegimeByHistoricalReqId.remove(reqId)?.let { gatewayRequestId ->
            val request = homeMarketRegimeRequests[gatewayRequestId] ?: return true
            val closeCount = request.dailyCloses.size
            ReversalScoreLog.homeMarketRegimeStage(
                benchmarkSymbol = request.benchmark.symbol,
                gatewayRequestId = gatewayRequestId,
                stage = "history_complete",
                detail = "attempt=${request.contractAttempt} closes=$closeCount " +
                    "exchange=${request.contractCandidates.getOrNull(request.contractAttempt)?.exchange()}"
            )
            if (!hasSufficientHomeMarketDailyCloses(closeCount)) {
                if (!advanceHomeMarketRegimeHistorical(gatewayRequestId, "insufficient_closes_$closeCount")) {
                    failHomeMarketRegime(
                        gatewayRequestId,
                        IllegalStateException(
                            "Need at least ${MacroRegimeEvaluator.SMA_WINDOW} daily closes for " +
                                "${request.benchmark.label} 200-SMA (got $closeCount)"
                        )
                    )
                }
                return true
            }
            requestPacer.enqueue {
                val liveReqId = nextMktDataReqId()
                homeMarketRegimeByLiveReqId[liveReqId] = gatewayRequestId
                val contract = IbContractMapper.forDataRequest(
                    request.contractCandidates[request.contractAttempt]
                )
                clientProvider().reqMktData(liveReqId, contract, "", true, false, null)
                scheduleHomeMarketRegimeLiveFallback(gatewayRequestId, liveReqId)
            }
            return true
        }
        spyByHistoricalReqId.remove(reqId)?.let { gatewayRequestId ->
            requestPacer.enqueue {
                val liveReqId = nextMktDataReqId()
                spyByLiveReqId[liveReqId] = gatewayRequestId
                val contract = IbContractMapper.forDataRequest(
                    IbContractMapper.contractForSymbol(SPY_SYMBOL, instrument = null)
                )
                clientProvider().reqMktData(liveReqId, contract, "", true, false, null)
                scheduleSpyLiveFallback(gatewayRequestId, liveReqId)
            }
            return true
        }
        return false
    }

    private fun scheduleSpyLiveFallback(gatewayRequestId: Long, liveReqId: Int) {
        scope.launch {
            delay(SNAPSHOT_FALLBACK_MS)
            if (spyByLiveReqId.remove(liveReqId) == null) return@launch
            clientProvider().cancelMktData(liveReqId)
            completeSpyRegime(gatewayRequestId)
        }
    }

    fun requestHomeMarketRegimeSnapshot(gatewayRequestId: Long, marketZoneId: String) {
        if (!isConnected()) {
            deliverHomeMarketRegime(
                gatewayRequestId,
                Result.failure(IllegalStateException("Not connected to IB Gateway"))
            )
            return
        }
        val benchmark = HomeMarketMacroBenchmark.forMarketZoneId(marketZoneId)
        val candidates = IbContractMapper.macroBenchmarkContractCandidates(benchmark.symbol)
        ReversalScoreLog.homeMarketRegimeFetchStarted(
            benchmarkSymbol = benchmark.symbol,
            benchmarkLabel = benchmark.label,
            gatewayRequestId = gatewayRequestId,
            marketZoneId = marketZoneId
        )
        homeMarketRegimeRequests[gatewayRequestId] = HomeMarketRegimeRequest(
            gatewayRequestId = gatewayRequestId,
            benchmark = benchmark,
            contractCandidates = candidates
        )
        scheduleHomeMarketRegimeTimeout(gatewayRequestId)
        enqueueHomeMarketRegimeHistorical(gatewayRequestId)
    }

    private fun enqueueHomeMarketRegimeHistorical(gatewayRequestId: Long) {
        requestPacer.enqueue {
            if (!isConnected()) {
                failHomeMarketRegime(
                    gatewayRequestId,
                    IllegalStateException("Disconnected before home-market regime request")
                )
                return@enqueue
            }
            val request = homeMarketRegimeRequests[gatewayRequestId] ?: return@enqueue
            if (request.contractAttempt >= request.contractCandidates.size) {
                failHomeMarketRegime(
                    gatewayRequestId,
                    IllegalStateException("No IB contract candidates left for ${request.benchmark.label}")
                )
                return@enqueue
            }
            request.dailyCloses.clear()
            request.liveLast = null
            val contract = IbContractMapper.forDataRequest(
                request.contractCandidates[request.contractAttempt]
            )
            ReversalScoreLog.homeMarketRegimeStage(
                benchmarkSymbol = request.benchmark.symbol,
                gatewayRequestId = gatewayRequestId,
                stage = "history_enqueued",
                detail = "attempt=${request.contractAttempt} exchange=${contract.exchange()}"
            )
            val reqId = nextHistoricalReqId()
            homeMarketRegimeByHistoricalReqId[reqId] = gatewayRequestId
            clientProvider().reqHistoricalData(
                reqId,
                contract,
                "",
                HOME_MARKET_REGIME_DURATION,
                SYMBOL_DAILY_BAR_SIZE,
                SYMBOL_DAILY_WHAT_TO_SHOW,
                0,
                1,
                false,
                null
            )
        }
    }

    private fun advanceHomeMarketRegimeHistorical(gatewayRequestId: Long, reason: String): Boolean {
        val request = homeMarketRegimeRequests[gatewayRequestId] ?: return false
        val nextAttempt = request.contractAttempt + 1
        if (nextAttempt >= request.contractCandidates.size) return false
        request.contractAttempt = nextAttempt
        ReversalScoreLog.homeMarketRegimeStage(
            benchmarkSymbol = request.benchmark.symbol,
            gatewayRequestId = gatewayRequestId,
            stage = "retry_contract",
            detail = "reason=$reason nextAttempt=$nextAttempt"
        )
        enqueueHomeMarketRegimeHistorical(gatewayRequestId)
        return true
    }

    fun requestSpyRegime(gatewayRequestId: Long) {
        if (!isConnected()) {
            val error = IllegalStateException("Not connected to IB Gateway")
            deliverSpyRegime(gatewayRequestId, Result.failure(error))
            return
        }
        spyRequests[gatewayRequestId] = SpyRegimeRequest(gatewayRequestId)
        scheduleSpyTimeout(gatewayRequestId)
        requestPacer.enqueue {
            if (!isConnected()) {
                failSpyRegime(gatewayRequestId, IllegalStateException("Disconnected before SPY regime request"))
                return@enqueue
            }
            val reqId = nextHistoricalReqId()
            spyByHistoricalReqId[reqId] = gatewayRequestId
            val contract = IbContractMapper.forDataRequest(
                IbContractMapper.contractForSymbol(SPY_SYMBOL, instrument = null)
            )
            clientProvider().reqHistoricalData(
                reqId,
                contract,
                "",
                SPY_REGIME_DURATION,
                SYMBOL_DAILY_BAR_SIZE,
                SYMBOL_DAILY_WHAT_TO_SHOW,
                1,
                1,
                false,
                null
            )
        }
    }

    fun onTickPrice(reqId: Int, field: Int, price: Double) {
        if (price <= 0.0) return
        homeMarketRegimeByLiveReqId[reqId]?.let { gatewayRequestId ->
            if (field == TickType.LAST.index() ||
                field == TickType.DELAYED_LAST.index() ||
                field == TickType.CLOSE.index() ||
                field == TickType.DELAYED_CLOSE.index()
            ) {
                homeMarketRegimeRequests[gatewayRequestId]?.liveLast = price
                clientProvider().cancelMktData(reqId)
                homeMarketRegimeByLiveReqId.remove(reqId)
                completeHomeMarketRegime(gatewayRequestId)
            }
            return
        }
        spyByLiveReqId[reqId]?.let { gatewayRequestId ->
            if (field == TickType.LAST.index() ||
                field == TickType.DELAYED_LAST.index() ||
                field == TickType.CLOSE.index() ||
                field == TickType.DELAYED_CLOSE.index()
            ) {
                spyRequests[gatewayRequestId]?.liveLast = price
                clientProvider().cancelMktData(reqId)
                spyByLiveReqId.remove(reqId)
                completeSpyRegime(gatewayRequestId)
            }
            return
        }
        symbolByLiveReqId[reqId]?.let { gatewayRequestId ->
            val request = symbolRequests[gatewayRequestId] ?: return
            when (field) {
                TickType.LAST.index(),
                TickType.DELAYED_LAST.index(),
                TickType.CLOSE.index(),
                TickType.DELAYED_CLOSE.index() -> request.live.lastPrice = price
                TickType.OPTION_IMPLIED_VOL.index() -> request.live.impliedVolatility = price / 100.0
            }
            return
        }
        macroByLiveReqId[reqId]?.let { (gatewayRequestId, index) ->
            if (field == TickType.LAST.index() ||
                field == TickType.DELAYED_LAST.index() ||
                field == TickType.CLOSE.index() ||
                field == TickType.DELAYED_CLOSE.index()
            ) {
                macroRequests[gatewayRequestId]?.liveValues?.put(index, price)
                clientProvider().cancelMktData(reqId)
                macroByLiveReqId.remove(reqId)
                macroRequests[gatewayRequestId]?.pendingLive?.remove(index)
                ReversalScoreLog.ibMacroStage(gatewayRequestId, "live_tick", "index=$index price=$price")
                maybeCompleteMacro(gatewayRequestId)
            }
        }
    }

    fun onTickSize(reqId: Int, field: Int, size: Decimal) {
        val gatewayRequestId = symbolByLiveReqId[reqId] ?: return
        if (!Decimal.isValid(size)) return
        if (field != TickType.VOLUME.index() && field != TickType.DELAYED_VOLUME.index()) return
        symbolRequests[gatewayRequestId]?.live?.volume = size.value().toDouble()
    }

    fun onSnapshotEnd(reqId: Int) {
        homeMarketRegimeByLiveReqId.remove(reqId)?.let { gatewayRequestId ->
            clientProvider().cancelMktData(reqId)
            completeHomeMarketRegime(gatewayRequestId)
            return
        }
        spyByLiveReqId.remove(reqId)?.let { gatewayRequestId ->
            clientProvider().cancelMktData(reqId)
            completeSpyRegime(gatewayRequestId)
            return
        }
        macroByLiveReqId.remove(reqId)?.let { (gatewayRequestId, index) ->
            clientProvider().cancelMktData(reqId)
            completeMacroLiveIndex(gatewayRequestId, index, reason = "tick_snapshot_end")
            return
        }
        symbolByLiveReqId.remove(reqId)?.let { gatewayRequestId ->
            clientProvider().cancelMktData(reqId)
            completeSymbol(gatewayRequestId)
        }
    }

    private fun completeSymbol(gatewayRequestId: Long) {
        val request = symbolRequests.remove(gatewayRequestId) ?: return
        symbolTimeoutJobs.remove(gatewayRequestId)?.cancel()
        val lastPrice = request.live.lastPrice
            ?: request.dailyBars.lastOrNull()?.close()?.takeIf { it > 0.0 }
        if (lastPrice == null || lastPrice <= 0.0) {
            val error = IllegalStateException("No price for reversal score")
            ReversalScoreLog.ibSymbolFailed(request.symbol, gatewayRequestId, error)
            deliverSymbol(gatewayRequestId, Result.failure(error))
            return
        }
        val volume = request.live.volume?.takeIf { it > 0.0 }
            ?: request.dailyBars.lastOrNull()?.barVolume()?.takeIf { it > 0.0 }
            ?: 0.0
        ReversalScoreLog.ibSymbolStage(
            request.symbol,
            gatewayRequestId,
            "live_complete",
            "last=${request.live.lastPrice} vol=${request.live.volume} iv=${request.live.impliedVolatility}"
        )
        val snapshot = ReversalScoreSymbolSnapshot(
            live = ReversalScoreLiveSnapshot(
                lastPrice = lastPrice,
                volume = volume,
                impliedVolatility = request.live.impliedVolatility
                    ?: request.ivBars.lastOrNull()?.close()?.takeIf { it > 0.0 }
            ),
            historical = ReversalScoreHistoricalSnapshot(
                dailyCloses = request.dailyBars.mapNotNull { bar -> bar.close().takeIf { it > 0.0 } },
                dailyVolumes = request.dailyBars.mapNotNull { bar ->
                    bar.barVolume().takeIf { it > 0.0 }
                },
                historicalIvValues = request.ivBars.mapNotNull { bar -> bar.close().takeIf { it > 0.0 } }
            )
        )
        ReversalScoreLog.ibSymbolDelivered(request.symbol, gatewayRequestId, snapshot)
        deliverSymbol(gatewayRequestId, Result.success(snapshot))
    }

    private fun maybeCompleteMacro(gatewayRequestId: Long) {
        val request = macroRequests[gatewayRequestId] ?: return
        if (request.pendingLive.isNotEmpty() || request.pendingHistory.isNotEmpty()) {
            ReversalScoreLog.ibMacroStage(
                gatewayRequestId,
                "pending",
                "live=${request.pendingLive.joinToString(",")} history=${request.pendingHistory.joinToString(",")}"
            )
            return
        }
        val vix = request.liveValues[MacroIndex.VIX]
            ?: request.histories[MacroIndex.VIX]?.lastOrNull()
        if (vix == null || vix <= 0.0) {
            failMacro(gatewayRequestId, IllegalStateException("No VIX data for reversal score"))
            return
        }
        macroRequests.remove(gatewayRequestId)
        macroTimeoutJobs.remove(gatewayRequestId)?.cancel()
        deliverMacro(
            gatewayRequestId,
            Result.success(
                ReversalScoreMacroVolSnapshot(
                    vix = vix,
                    vix1d = request.liveValues[MacroIndex.VIX1D] ?: request.histories[MacroIndex.VIX1D]?.lastOrNull(),
                    vvix = request.liveValues[MacroIndex.VVIX] ?: request.histories[MacroIndex.VVIX]?.lastOrNull(),
                    vixHistory = request.histories[MacroIndex.VIX].orEmpty().ifEmpty { defaultMacroHistory(MacroIndex.VIX) },
                    vix1dHistory = request.histories[MacroIndex.VIX1D].orEmpty().ifEmpty { defaultMacroHistory(MacroIndex.VIX1D) },
                    vvixHistory = request.histories[MacroIndex.VVIX].orEmpty().ifEmpty { defaultMacroHistory(MacroIndex.VVIX) }
                )
            )
        )
    }

    private fun defaultMacroHistory(index: MacroIndex): List<Double> = when (index) {
        MacroIndex.VIX -> List(60) { 16.0 + (it % 6) }
        MacroIndex.VIX1D -> List(60) { 18.0 + (it % 5) }
        MacroIndex.VVIX -> List(60) { 90.0 + (it % 7) }
    }

    private fun macroContract(index: MacroIndex): Contract =
        IbContractMapper.forDataRequest(
            Contract().apply {
                symbol(
                    when (index) {
                        MacroIndex.VIX -> "VIX"
                        MacroIndex.VIX1D -> "VIX1D"
                        MacroIndex.VVIX -> "VVIX"
                    }
                )
                secType(Types.SecType.IND.name)
                exchange("CBOE")
                currency("USD")
            }
        )

    private fun scheduleSymbolTimeout(gatewayRequestId: Long) {
        symbolTimeoutJobs[gatewayRequestId]?.cancel()
        symbolTimeoutJobs[gatewayRequestId] = scope.launch {
            delay(QueuedBrokerGateway.REVERSAL_SCORE_REQUEST_TIMEOUT_MS)
            if (symbolRequests.containsKey(gatewayRequestId)) {
                failSymbol(gatewayRequestId, IllegalStateException("Reversal score symbol request timed out"))
            }
        }
    }

    private fun scheduleMacroTimeout(gatewayRequestId: Long) {
        macroTimeoutJobs[gatewayRequestId]?.cancel()
        macroTimeoutJobs[gatewayRequestId] = scope.launch {
            delay(QueuedBrokerGateway.REVERSAL_SCORE_REQUEST_TIMEOUT_MS)
            val request = macroRequests[gatewayRequestId] ?: return@launch
            ReversalScoreLog.ibMacroStage(
                gatewayRequestId,
                "timeout",
                "live=${request.pendingLive.joinToString(",")} history=${request.pendingHistory.joinToString(",")}"
            )
            request.pendingHistory.clear()
            request.pendingLive.toList().forEach { index ->
                completeMacroLiveIndex(gatewayRequestId, index, reason = "timeout_fallback")
            }
            if (macroRequests.containsKey(gatewayRequestId)) {
                failMacro(gatewayRequestId, IllegalStateException("Reversal score macro request timed out"))
            }
        }
    }

    private fun failSymbol(gatewayRequestId: Long, error: Throwable) {
        symbolRequests.remove(gatewayRequestId)?.let { request ->
            ReversalScoreLog.ibSymbolFailed(request.symbol, gatewayRequestId, error)
        }
        symbolTimeoutJobs.remove(gatewayRequestId)?.cancel()
        deliverSymbol(gatewayRequestId, Result.failure(error))
    }

    private fun failMacro(gatewayRequestId: Long, error: Throwable) {
        ReversalScoreLog.ibMacroFailed(gatewayRequestId, error)
        macroRequests.remove(gatewayRequestId)
        macroTimeoutJobs.remove(gatewayRequestId)?.cancel()
        deliverMacro(gatewayRequestId, Result.failure(error))
    }

    private fun scheduleHomeMarketRegimeLiveFallback(gatewayRequestId: Long, liveReqId: Int) {
        scope.launch {
            delay(SNAPSHOT_FALLBACK_MS)
            if (homeMarketRegimeByLiveReqId.remove(liveReqId) == null) return@launch
            clientProvider().cancelMktData(liveReqId)
            completeHomeMarketRegime(gatewayRequestId)
        }
    }

    private fun scheduleHomeMarketRegimeTimeout(gatewayRequestId: Long) {
        homeMarketRegimeTimeoutJobs[gatewayRequestId]?.cancel()
        homeMarketRegimeTimeoutJobs[gatewayRequestId] = scope.launch {
            delay(QueuedBrokerGateway.REVERSAL_SCORE_REQUEST_TIMEOUT_MS)
            if (homeMarketRegimeRequests.containsKey(gatewayRequestId)) {
                completeHomeMarketRegime(gatewayRequestId)
            }
        }
    }

    private fun completeHomeMarketRegime(gatewayRequestId: Long) {
        val request = homeMarketRegimeRequests.remove(gatewayRequestId) ?: return
        homeMarketRegimeTimeoutJobs.remove(gatewayRequestId)?.cancel()
        val lastPrice = request.liveLast ?: request.dailyCloses.lastOrNull()
        if (lastPrice == null || lastPrice <= 0.0) {
            failHomeMarketRegime(
                gatewayRequestId,
                IllegalStateException("No ${request.benchmark.label} price for regime snapshot")
            )
            return
        }
        val snapshotResult = MacroRegimeEvaluator.buildSnapshot(
            benchmark = request.benchmark,
            lastPrice = lastPrice,
            dailyCloses = request.dailyCloses
        )
        snapshotResult.onSuccess { snapshot ->
            ReversalScoreLog.homeMarketRegimeDelivered(
                benchmarkSymbol = request.benchmark.symbol,
                gatewayRequestId = gatewayRequestId,
                lastPrice = snapshot.lastPrice,
                sma200 = snapshot.sma200,
                dailyCloseCount = snapshot.dailyCloses.size,
                trend = snapshot.macroTrendState()?.name
            )
        }
        deliverHomeMarketRegime(gatewayRequestId, snapshotResult)
    }

    private fun failHomeMarketRegime(gatewayRequestId: Long, error: Throwable) {
        val request = homeMarketRegimeRequests.remove(gatewayRequestId)
        homeMarketRegimeTimeoutJobs.remove(gatewayRequestId)?.cancel()
        ReversalScoreLog.homeMarketRegimeFailed(
            benchmarkSymbol = request?.benchmark?.symbol ?: "unknown",
            gatewayRequestId = gatewayRequestId,
            error = error
        )
        deliverHomeMarketRegime(gatewayRequestId, Result.failure(error))
    }

    private fun deliverHomeMarketRegime(gatewayRequestId: Long, result: Result<MacroRegimeSnapshot>) {
        emit(GatewayEvent.HomeMarketRegimeSnapshotReady(gatewayRequestId, result))
    }

    private fun scheduleSpyTimeout(gatewayRequestId: Long) {
        spyTimeoutJobs[gatewayRequestId]?.cancel()
        spyTimeoutJobs[gatewayRequestId] = scope.launch {
            delay(QueuedBrokerGateway.REVERSAL_SCORE_REQUEST_TIMEOUT_MS)
            if (spyRequests.containsKey(gatewayRequestId)) {
                completeSpyRegime(gatewayRequestId)
            }
        }
    }

    private fun completeSpyRegime(gatewayRequestId: Long) {
        val request = spyRequests.remove(gatewayRequestId) ?: return
        spyTimeoutJobs.remove(gatewayRequestId)?.cancel()
        val lastPrice = request.liveLast ?: request.dailyCloses.lastOrNull()
        if (lastPrice == null || lastPrice <= 0.0) {
            failSpyRegime(gatewayRequestId, IllegalStateException("No SPY price for regime snapshot"))
            return
        }
        val snapshotResult = SpyRegimeEvaluator.buildSnapshot(lastPrice, request.dailyCloses)
        deliverSpyRegime(gatewayRequestId, snapshotResult)
    }

    private fun failSpyRegime(gatewayRequestId: Long, error: Throwable) {
        spyRequests.remove(gatewayRequestId)
        spyTimeoutJobs.remove(gatewayRequestId)?.cancel()
        deliverSpyRegime(gatewayRequestId, Result.failure(error))
    }

    private fun deliverSpyRegime(gatewayRequestId: Long, result: Result<SpyRegimeSnapshot>) {
        emit(GatewayEvent.SpyRegimeSnapshotReady(gatewayRequestId, result))
    }

    private fun deliverSymbol(gatewayRequestId: Long, result: Result<ReversalScoreSymbolSnapshot>) {
        emit(GatewayEvent.ReversalScoreSymbolSnapshotReady(gatewayRequestId, result))
    }

    private fun deliverMacro(gatewayRequestId: Long, result: Result<ReversalScoreMacroVolSnapshot>) {
        result.onSuccess { macro -> ReversalScoreLog.ibMacroDelivered(gatewayRequestId, macro) }
        emit(GatewayEvent.ReversalScoreMacroVolatilityReady(gatewayRequestId, result))
    }

    private fun Bar.barVolume(): Double {
        val raw = volume()
        return if (Decimal.isValid(raw)) raw.value().toDouble() else 0.0
    }

    private companion object {
        const val SPY_SYMBOL = "SPY"
        const val SPY_REGIME_DURATION = "200 D"
        // IB rejects day-based durations >365; need years for 200-day SMA window.
        const val HOME_MARKET_REGIME_DURATION = "2 Y"
        const val SYMBOL_DAILY_DURATION = "30 D"
        const val SYMBOL_IV_DURATION = "1 Y"
        const val SYMBOL_DAILY_BAR_SIZE = "1 day"
        const val SYMBOL_DAILY_WHAT_TO_SHOW = "TRADES"
        const val SYMBOL_IV_WHAT_TO_SHOW = "OPTION_IMPLIED_VOLATILITY"
        const val MACRO_HISTORY_DURATION = "60 D"
        const val REVERSAL_SCORE_GENERIC_TICKS = "233,106"
        const val SNAPSHOT_FALLBACK_MS = 4_000L
    }
}
