package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleColor
import daytrader.domain.MacroTrendState
import daytrader.domain.RthMarketSessions
import daytrader.domain.StockTrendState
import daytrader.domain.InstrumentPriceScale
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.diagnostics.EmulatorPriceLog
import daytrader.gateway.BrokerFill
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import daytrader.gateway.OpenOrderBook
import daytrader.gateway.TouchTurnBracketAck
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * In-memory **exchange emulation**: connection lifecycle, positions, working orders, and fills.
 *
 * Order triggers read bid/ask/last from [quoteBook], which is fed either by [SyntheticQuoteSimulator]
 * ([EmulatorPricingSource.SYNTHETIC]) or [ingestExternalQuote] ([EmulatorPricingSource.LIVE_EXCHANGE]).
 */
class BrokerEmulatorEngine(
    private val config: BrokerEmulatorConfig = BrokerEmulatorConfig.Default,
    private val emit: (GatewayEvent) -> Unit,
    private val onSymbolNeedsLiveQuotes: (String) -> Unit = {},
    private val random: Random = Random.Default
) {
    private val catalog = EmulatorSeedCatalog.instruments()
    private val quoteBook = EmulatorQuoteBook(config.pricingSource)
    private val syntheticQuotes = SyntheticQuoteSimulator(config, quoteBook, random)
    private var positions = mutableListOf<EmulatorPosition>()
    private var orders = mutableMapOf<Int, EmulatorOrder>()
    private val openOrderBook = OpenOrderBook()
    private var lastPublishedOpenOrdersFingerprint = ""
    private var nextOrderId = 1_000
    private var connected = false
    private var ticksRunning = false
    private var orderSimRunning = false
    private val dynamicInstruments = mutableMapOf<String, EmulatorInstrument>()
    private val bracketManagedOrderIds = mutableSetOf<Int>()
    private val bracketPriceWalks = mutableMapOf<String, BracketPriceWalk>()
    /** Exit walk prepared at bracket place; armed only after entry fills. */
    private val pendingBracketWalks = mutableMapOf<String, BracketPriceWalk>()
    private val bracketEntryPending = mutableMapOf<String, BracketEntryPending>()
    private val sessionFills = mutableListOf<BrokerFill>()
    private var firstCandleFetchCount = 0
    /** Bootstrap fetch index per symbol; refetch reuses without incrementing. */
    private val lockedCandleFetchIndexBySymbol = mutableMapOf<String, Int>()
    /** Touch Turn symbol registered per RTH zone after bootstrap/refetch (drives aligned trend mocks). */
    private val touchTurnSymbolByZone = mutableMapOf<String, String>()
    private val externalFeedReadyLogged = mutableSetOf<String>()
    /** Per-symbol holders for synthetic streaming quotes (session-scoped, like IB hybrid). */
    private val streamSubscriptionRefCount = mutableMapOf<String, Int>()

    fun handleConnect() {
        if (connected) return
        EmulatorLog.connectionState("connecting", config.pricingSource.name)
        emit(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Connecting))
    }

    suspend fun finishConnect() {
        delay(config.connectDelayMs)
        seedBooks()
        connected = true
        EmulatorLog.connectionState("connected", config.pricingSource.name)
        emit(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Connected))
        publishPositions()
        finishEmulatedOpenOrdersLoad()
        if (!config.pricingSource.isSynthetic || hasActiveStreamingSubscriptions()) {
            publishQuotes()
        }
        startMarketSimulation()
        startOrderSimulation()
    }

    /**
     * Arms synthetic quote streaming for [symbol]. Refcounted so multiple sessions can share one symbol.
     * No-op in [EmulatorPricingSource.LIVE_EXCHANGE] mode (hybrid uses IB for live marks).
     */
    fun ensureStreamingMarketData(
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity? = null
    ) {
        if (!config.pricingSource.isSynthetic) return
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        if (!incrementStreamRefCount(norm)) return
        seedSymbolQuote(norm)
        publishQuotes()
    }

    /**
     * Drops synthetic streaming for [symbol] when the last holder releases it
     * (e.g. when no deployment session is running for that symbol).
     */
    fun releaseStreamingMarketData(
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity? = null
    ) {
        if (!config.pricingSource.isSynthetic) return
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        if (!decrementStreamRefCount(norm)) return
        quoteBook.removeSymbol(norm)
        bracketPriceWalks.remove(norm)
        bracketEntryPending.remove(norm)
        pendingBracketWalks.remove(norm)
        publishQuotes()
    }

    fun handleDisconnect() {
        connected = false
        ticksRunning = false
        streamSubscriptionRefCount.clear()
        orderSimRunning = false
        EmulatorLog.connectionState("disconnected", config.pricingSource.name)
        positions.clear()
        orders.clear()
        openOrderBook.clear()
        lastPublishedOpenOrdersFingerprint = ""
        bracketManagedOrderIds.clear()
        bracketPriceWalks.clear()
        dynamicInstruments.clear()
        quoteBook.clear()
        EmulatorPriceLog.clearState()
        externalFeedReadyLogged.clear()
        pendingBracketWalks.clear()
        bracketEntryPending.clear()
        lockedCandleFetchIndexBySymbol.clear()
        touchTurnSymbolByZone.clear()
        emit(GatewayEvent.PositionsSnapshot(emptyList()))
        emit(GatewayEvent.OpenOrdersSnapshot(emptyList()))
        emit(GatewayEvent.QuotesSnapshot(emptyMap()))
        sessionFills.clear()
        emit(GatewayEvent.FillsSnapshot(emptyList()))
        emit(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Disconnected))
    }

    suspend fun handleReconnect() {
        EmulatorLog.connectionState("reconnecting", config.pricingSource.name)
        handleDisconnect()
        delay(config.reconnectDelayMs)
        handleConnect()
        finishConnect()
    }

    fun handleShutdown() {
        EmulatorLog.connectionState("shutdown", config.pricingSource.name)
        handleDisconnect()
    }

    suspend fun fetchFirstFifteenMinuteCandle(requestId: Long, symbol: String) {
        delay(config.historicalDelayMs)
        val trimmed = symbol.trim().uppercase()
        val instrument = resolveInstrument(trimmed)
        val result = if (instrument == null) {
            val message = "Unknown symbol: $symbol"
            EmulatorLog.historicalFetchFailed("first_candle", trimmed, message)
            Result.failure(IllegalArgumentException(message))
        } else {
            val fetchIndex = resolveSessionCandleFetchIndex(trimmed, isClosedBarRefetch = false)
            registerTouchTurnSessionSymbol(trimmed, instrument)
            val candleResult = EmulatorHistoricalData.firstFifteenMinuteCandle(
                symbol = trimmed,
                instrument = instrument,
                config = config,
                sessionCandleFetchIndex = fetchIndex
            )
            candleResult.onSuccess { bar ->
                config.firstCandleSecondsUntilClose?.let { seconds ->
                    EmulatorLog.firstCandleScheduled(trimmed, bar.time.orEmpty(), seconds)
                }
                EmulatorLog.firstCandleColor(
                    symbol = trimmed,
                    isGreen = TouchTurnLogic.firstCandleColor(bar) == FirstCandleColor.GREEN,
                    fetchIndex = fetchIndex,
                    colorMode = config.firstCandleColorMode,
                    isClosedBarRefetch = false
                )
            }
            candleResult
        }
        emit(GatewayEvent.FirstFifteenMinuteCandleReady(requestId, result))
    }

    suspend fun fetchFourteenDayAdr(requestId: Long, symbol: String) {
        delay(config.historicalDelayMs)
        val trimmed = symbol.trim().uppercase()
        val instrument = resolveInstrument(trimmed)
        val result = if (instrument == null) {
            val message = "Unknown symbol: $symbol"
            EmulatorLog.historicalFetchFailed("adr", trimmed, message)
            Result.failure(IllegalArgumentException(message))
        } else {
            EmulatorHistoricalData.fourteenDayAdr(trimmed, instrument)
        }
        emit(GatewayEvent.FourteenDayAdrReady(requestId, result))
    }

    suspend fun fetchLatestDailyClose(
        requestId: Long,
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        delay(config.historicalDelayMs)
        val trimmed = symbol.trim().uppercase()
        val norm = SymbolMarkets.normalizeSymbol(trimmed)
        val quote = quoteBook.quoteOrNull(norm)
        val fromQuote = quote?.last?.takeIf { it > 0.0 }
            ?: quote?.let { q ->
                val bid = q.bid?.takeIf { it > 0.0 }
                val ask = q.ask?.takeIf { it > 0.0 }
                if (bid != null && ask != null) (bid + ask) / 2.0 else null
            }
        if (fromQuote != null) {
            emit(GatewayEvent.LatestDailyCloseReady(requestId, Result.success(fromQuote)))
            return
        }
        val resolved = resolveInstrument(trimmed)
        val result = if (resolved == null) {
            Result.failure(IllegalArgumentException("Unknown symbol: $symbol"))
        } else {
            val sessionDay = TouchTurnLogic.sessionDayYyyyMmDd(resolved.marketZoneId, System.currentTimeMillis())
            val bars = EmulatorHistoricalData.buildDailyBars(trimmed, resolved, sessionDay)
            bars.lastOrNull()?.close?.takeIf { it > 0.0 }
                ?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("No emulator daily close for $symbol"))
        }
        emit(GatewayEvent.LatestDailyCloseReady(requestId, result))
    }

    suspend fun fetchReversalScoreSymbolSnapshot(
        requestId: Long,
        symbol: String,
        instrument: daytrader.domain.InstrumentIdentity?
    ) {
        delay(config.historicalDelayMs)
        val trimmed = symbol.trim().uppercase()
        val resolved = resolveInstrument(trimmed)
        val result = if (resolved == null) {
            Result.failure(IllegalArgumentException("Unknown symbol: $symbol"))
        } else {
            val sessionDay = TouchTurnLogic.sessionDayYyyyMmDd(resolved.marketZoneId, System.currentTimeMillis())
            val bars = EmulatorHistoricalData.buildDailyBars(trimmed, resolved, sessionDay)
            val lastPrice = bars.lastOrNull()?.close?.takeIf { it > 0.0 }
            if (lastPrice == null) {
                Result.failure(IllegalStateException("No emulator daily bars for $symbol"))
            } else {
                val norm = SymbolMarkets.normalizeSymbol(trimmed)
                val stockTrend = config.stockTrendBySymbol[norm]
                    ?: alignedStockTrendForSymbol(norm)
                    ?: StockTrendState.UP
                val closes = if (config.stockTrendBySymbol.containsKey(norm)) {
                    daytrader.domain.StockTrendEvaluator.historicalClosesForTrend(
                        lastPrice = lastPrice,
                        trend = stockTrend
                    )
                } else {
                    daytrader.domain.StockTrendEvaluator.paddedDailyCloses(
                        lastPrice = lastPrice,
                        dailyCloses = bars.map { it.close },
                        trend = stockTrend
                    )
                }
                val resolvedLast = closes.last()
                Result.success(
                    daytrader.data.ReversalScoreService.syntheticSymbolSnapshot(resolvedLast).copy(
                        historical = daytrader.domain.ReversalScoreHistoricalSnapshot(
                            dailyCloses = closes,
                            dailyVolumes = closes.map { it * 10_000.0 },
                            historicalIvValues = closes.map { 0.15 + (it.toInt() % 7) * 0.005 }
                        )
                    )
                )
            }
        }
        emit(GatewayEvent.ReversalScoreSymbolSnapshotReady(requestId, result))
    }

    suspend fun fetchReversalScoreMacroVolatility(requestId: Long) {
        delay(config.historicalDelayMs)
        emit(
            GatewayEvent.ReversalScoreMacroVolatilityReady(
                requestId,
                Result.success(daytrader.data.ReversalScoreService.syntheticMacroVolSnapshot())
            )
        )
    }

    suspend fun fetchSpyRegimeSnapshot(requestId: Long) {
        delay(config.historicalDelayMs)
        val result = runCatching {
            buildHomeMarketRegimeSnapshot(RthMarketSessions.US.zoneId).getOrThrow()
                .let { macro ->
                    daytrader.domain.SpyRegimeSnapshot(
                        lastPrice = macro.lastPrice,
                        sma200 = macro.sma200,
                        dailyCloses = macro.dailyCloses
                    )
                }
        }
        emit(GatewayEvent.SpyRegimeSnapshotReady(requestId, result))
    }

    suspend fun fetchHomeMarketRegimeSnapshot(requestId: Long, marketZoneId: String) {
        delay(config.historicalDelayMs)
        val result = buildHomeMarketRegimeSnapshot(marketZoneId)
        emit(GatewayEvent.HomeMarketRegimeSnapshotReady(requestId, result))
    }

    private fun buildHomeMarketRegimeSnapshot(marketZoneId: String): Result<daytrader.domain.MacroRegimeSnapshot> =
        runCatching {
            val benchmark = daytrader.domain.HomeMarketMacroBenchmark.forMarketZoneId(marketZoneId)
            val resolved = resolveInstrument(benchmark.symbol)
                ?: error("Unknown macro benchmark: ${benchmark.symbol}")
            val lastPrice = resolved.referencePrice.takeIf { it > 0.0 }
                ?: error("No emulator price for ${benchmark.symbol}")
            val trend = config.homeMacroTrendByZone[marketZoneId]
                ?: alignedMacroTrendForZone(marketZoneId)
                ?: MacroTrendState.BULL
            daytrader.domain.MacroRegimeEvaluator.buildSyntheticSnapshot(
                benchmark = benchmark,
                lastPrice = lastPrice,
                trend = trend
            )
        }

    suspend fun fetchTouchTurnSignalContext(
        requestId: Long,
        symbol: String,
        isClosedBarRefetch: Boolean = false,
        rules: daytrader.domain.TouchTurnRuleConfig = daytrader.domain.TouchTurnRuleConfig.DEFAULT
    ) {
        delay(config.historicalDelayMs)
        val trimmed = symbol.trim().uppercase()
        val instrument = resolveInstrument(trimmed)
        val result = if (instrument == null) {
            Result.failure(IllegalArgumentException("Unknown symbol: $symbol"))
        } else {
            val fetchIndex = resolveSessionCandleFetchIndex(trimmed, isClosedBarRefetch)
            registerTouchTurnSessionSymbol(trimmed, instrument)
            EmulatorHistoricalData.touchTurnSignalContext(
                symbol = trimmed,
                instrument = instrument,
                config = config,
                sessionCandleFetchIndex = fetchIndex,
                rules = rules
            ).also { contextResult ->
                contextResult.onSuccess { context ->
                    EmulatorLog.firstCandleColor(
                        symbol = trimmed,
                        isGreen = TouchTurnLogic.firstCandleColor(context.firstCandle) == FirstCandleColor.GREEN,
                        fetchIndex = fetchIndex,
                        colorMode = config.firstCandleColorMode,
                        isClosedBarRefetch = isClosedBarRefetch
                    )
                }
            }
        }
        emit(GatewayEvent.TouchTurnSignalContextReady(requestId, result))
    }

    private fun registerTouchTurnSessionSymbol(symbol: String, instrument: EmulatorInstrument) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        touchTurnSymbolByZone[instrument.marketZoneId] = norm
    }

    /**
     * Fade alignment: green short → bear macro / down stock; red long → bull macro / up stock.
     * Used when trend-rule overrides are absent so emulator sessions complete end-to-end.
     */
    private fun alignedMacroTrendForZone(marketZoneId: String): MacroTrendState? =
        touchTurnSymbolByZone[marketZoneId]?.let { alignedMacroTrendForSymbol(it) }

    private fun alignedStockTrendForSymbol(norm: String): StockTrendState? =
        when (resolveFirstCandleIsGreenForSymbol(norm)) {
            true -> StockTrendState.DOWN
            false -> StockTrendState.UP
            null -> null
        }

    private fun alignedMacroTrendForSymbol(norm: String): MacroTrendState? =
        when (resolveFirstCandleIsGreenForSymbol(norm)) {
            true -> MacroTrendState.BEAR
            false -> MacroTrendState.BULL
            null -> null
        }

    private fun resolveFirstCandleIsGreenForSymbol(symbol: String): Boolean? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val instrument = resolveInstrument(norm) ?: return null
        val sessionDay = TouchTurnLogic.sessionDayYyyyMmDd(instrument.marketZoneId, System.currentTimeMillis())
        val fetchIndex = lockedCandleFetchIndexBySymbol[norm] ?: return null
        return EmulatorHistoricalData.resolveFirstCandleIsGreen(
            norm = norm,
            sessionYmd = sessionDay,
            colorMode = config.firstCandleColorMode,
            sessionCandleFetchIndex = fetchIndex,
            alternateFirstCandleColor = config.alternateFirstCandleColor
        )
    }

    /**
     * When [alternateFirstCandleColor] is on, each Touch Turn **session** (bootstrap + refetch)
     * shares one index; only bootstrap increments so refetch does not flip green/red.
     */
    private fun resolveSessionCandleFetchIndex(symbol: String, isClosedBarRefetch: Boolean): Int {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (!config.alternateFirstCandleColor ||
            config.firstCandleColorMode != EmulatorFirstCandleColorMode.AUTO
        ) {
            return 0
        }
        if (isClosedBarRefetch) {
            return lockedCandleFetchIndexBySymbol[norm] ?: firstCandleFetchCount.coerceAtLeast(1)
        }
        firstCandleFetchCount++
        lockedCandleFetchIndexBySymbol[norm] = firstCandleFetchCount
        return firstCandleFetchCount
    }

    fun cancelOrder(orderId: Int) {
        if (!connected) return
        val order = orders[orderId] ?: return
        if (order.isTerminal()) return
        updateOrder(order.copy(status = "Cancelled"))
        EmulatorLog.sessionOrdersCancelled(order.symbol, 1)
        publishOrders()
    }

    fun resizeTouchTurnBracket(request: daytrader.domain.TouchTurnBracketResizeRequest): Result<Int> {
        if (!connected) {
            return Result.failure(IllegalStateException("not_connected"))
        }
        val parent = orders[request.orderIds.parentOrderId]
            ?: return Result.failure(IllegalArgumentException("parent_order_missing"))
        if (parent.filled > 0 || parent.remaining <= 0) {
            return Result.failure(IllegalStateException("entry_already_filled"))
        }
        val newQty = request.plan.quantity
        if (newQty <= 0) {
            return Result.failure(IllegalArgumentException("invalid_quantity"))
        }
        request.orderIds.allIds.forEach { orderId ->
            val order = orders[orderId] ?: return Result.failure(IllegalArgumentException("leg_missing_$orderId"))
            if (order.filled > 0) {
                return Result.failure(IllegalStateException("leg_partially_filled"))
            }
            val updated = order.copy(quantity = newQty, remaining = newQty)
            orders[orderId] = updated
            syncOrderToOpenBook(orderId)
        }
        publishOrders()
        EmulatorLog.bracketResized(request.symbol, newQty, request.orderIds.allIds)
        return Result.success(newQty)
    }

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) {
        val symbolForAck = SymbolMarkets.normalizeSymbol(plan.symbol)
        if (!connected) {
            EmulatorLog.bracketRejected(plan.symbol, "not_connected")
            val failure = TouchTurnBracketAck(
                symbol = symbolForAck,
                orderIds = emptyList(),
                result = Result.failure(IllegalStateException("not_connected")),
                plan = plan
            )
            emit(GatewayEvent.TouchTurnBracketPlaced(failure))
            EmulatorLog.bracketAckEmitted(symbolForAck, emptyList(), success = false, openOrderCount = 0, error = "not_connected")
            return
        }
        val adjustedPlan = EmulatorBracketPlanAdjuster.widenExits(
            plan = plan,
            spreadWidenFactor = config.bracketExitSpreadWidenFactor
        )
        val symbol = SymbolMarkets.normalizeSymbol(adjustedPlan.symbol)
        val openPositionQty = positions.firstOrNull {
            SymbolMarkets.symbolsMatch(it.instrument.symbol, symbol) && it.quantity != 0
        }?.quantity
        if (openPositionQty != null) {
            EmulatorLog.bracketRejected(symbol, "open_position_exists")
            val failure = TouchTurnBracketAck(
                symbol = symbolForAck,
                orderIds = emptyList(),
                result = Result.failure(IllegalStateException("open_position_exists")),
                plan = plan
            )
            emit(GatewayEvent.TouchTurnBracketPlaced(failure))
            EmulatorLog.bracketAckEmitted(
                symbolForAck,
                emptyList(),
                success = false,
                openOrderCount = openOrderBook.snapshot().size,
                error = "open_position_exists"
            )
            return
        }
        if (config.pricingSource.isSynthetic) {
            ensureStreamingMarketData(symbol, adjustedPlan.instrument)
        }
        val entryLeg = adjustedPlan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY } ?: run {
            val failure = TouchTurnBracketAck(
                symbol = symbolForAck,
                orderIds = emptyList(),
                result = Result.failure(IllegalArgumentException("missing_entry_leg")),
                plan = plan
            )
            emit(GatewayEvent.TouchTurnBracketPlaced(failure))
            EmulatorLog.bracketAckEmitted(symbolForAck, emptyList(), success = false, openOrderCount = 0, error = "missing_entry_leg")
            return
        }
        val entryPrice = entryLeg.price

        orders.entries.removeIf { (_, order) ->
            SymbolMarkets.symbolsMatch(order.symbol, symbol) && !order.isTerminal()
        }
        openOrderBook.snapshot()
            .filter { SymbolMarkets.symbolsMatch(it.symbol, symbol) }
            .forEach { openOrderBook.removeOrder(it.orderId) }
        bracketManagedOrderIds.removeIf { id -> orders[id]?.let { SymbolMarkets.symbolsMatch(it.symbol, symbol) } == true }

        ensureInstrument(symbol, adjustedPlan.currencyCode, entryPrice, adjustedPlan.instrument)

        val entryId = allocateOrderId()
        bracketManagedOrderIds.add(entryId)
        onEmulatedOpenOrder(
            plannedToEmulatorOrder(
                orderId = entryId,
                planned = entryLeg,
                symbol = symbol,
                currency = adjustedPlan.currencyCode,
                parentId = 0,
                status = "Submitted"
            )
        )

        val childIds = mutableListOf<Int>()
        adjustedPlan.orders.filter { it.role != TouchTurnOrderRole.ENTRY }.forEach { leg ->
            val childId = allocateOrderId()
            childIds.add(childId)
            bracketManagedOrderIds.add(childId)
            onEmulatedOpenOrder(
                plannedToEmulatorOrder(
                    orderId = childId,
                    planned = leg,
                    symbol = symbol,
                    currency = adjustedPlan.currencyCode,
                    parentId = entryId,
                    status = "PreSubmitted"
                )
            )
        }
        val allOrderIds = listOf(entryId) + childIds

        if (config.pricingSource.isSynthetic) {
            val legPrices = adjustedPlan.orders.map { it.price }
            val floor = legPrices.min()
            val ceiling = legPrices.max()
            val range = (ceiling - floor).coerceAtLeast(0.01)
            val takeProfit = EmulatorBracketPlanAdjuster.takeProfitPrice(adjustedPlan) ?: ceiling
            val stopLoss = EmulatorBracketPlanAdjuster.stopLossPrice(adjustedPlan) ?: floor
            val towardTp = EmulatorBracketPlanAdjuster.towardTakeProfitDirection(adjustedPlan)
            val targetExit = pickBracketExitTarget()
            val isBuyEntry = entryLeg.action.equals("BUY", ignoreCase = true)
            val isStopEntry = entryLeg.orderType.equals("STP", ignoreCase = true)
            pendingBracketWalks[symbol] = BracketPriceWalk(
                floor = floor,
                ceiling = ceiling,
                takeProfitPrice = takeProfit,
                stopLossPrice = stopLoss,
                towardTakeProfitDirection = towardTp,
                targetExit = targetExit,
                isLongPosition = isBuyEntry,
                direction = directionTowardTarget(towardTp, targetExit)
            )
            val startOffset = (range * config.touchTurnEntryStartOffsetPctOfRange).coerceAtLeast(0.01)
            val initialMarkFromOffset = when {
                isStopEntry && isBuyEntry ->
                    // Buy stop: start below entry so price must rally through the level.
                    entryPrice - startOffset
                isStopEntry ->
                    // Sell stop: start above entry so price must break down through the level.
                    entryPrice + startOffset
                isBuyEntry ->
                    // Buy limit should start above entry so price has to come down to fill.
                    entryPrice + startOffset
                else ->
                    // Sell limit should start below entry so price has to rise to fill.
                    entryPrice - startOffset
            }
            val initialMark = initialMarkFromOffset.coerceIn(floor, ceiling)
            val spread = EmulatorMarketQuoteBook.spreadForBracketRange(
                range = range,
                referencePrice = initialMark,
                spreadPctOfRange = config.emulatorQuoteSpreadPctOfRange
            )
            quoteFor(symbol).setFromBarClose(initialMark, spread)
            val entryScenario = pickTouchTurnEntryScenario()
            when (entryScenario) {
                TouchTurnEntryScenario.IMMEDIATE -> {
                    setQuoteMid(symbol, entryPrice)
                    fillEntryImmediately(entryId)
                }
                TouchTurnEntryScenario.APPROACH_AND_FILL,
                TouchTurnEntryScenario.NEVER_FILL -> {
                    bracketEntryPending[symbol] = BracketEntryPending(
                        entryOrderId = entryId,
                        entryPrice = entryPrice,
                        openingBarClose = initialMark,
                        isBuyEntry = isBuyEntry,
                        isStopEntry = isStopEntry,
                        scenario = entryScenario,
                        range = range
                    )
                }
            }
            EmulatorLog.bracketPlaced(
                symbol = symbol,
                orderIds = allOrderIds,
                entryPrice = entryPrice,
                initialMark = initialMark,
                walkFloor = floor,
                walkCeiling = ceiling,
                entryScenario = entryScenario
            )
        } else {
            val legPrices = adjustedPlan.orders.map { it.price }
            val range = (legPrices.max() - legPrices.min()).coerceAtLeast(0.01)
            val isBuyEntry = entryLeg.action.equals("BUY", ignoreCase = true)
            val isStopEntry = entryLeg.orderType.equals("STP", ignoreCase = true)
            val entryScenario = pickTouchTurnEntryScenario()
            when (entryScenario) {
                TouchTurnEntryScenario.IMMEDIATE -> fillEntryImmediately(entryId)
                TouchTurnEntryScenario.APPROACH_AND_FILL,
                TouchTurnEntryScenario.NEVER_FILL -> {
                    bracketEntryPending[symbol] = BracketEntryPending(
                        entryOrderId = entryId,
                        entryPrice = entryPrice,
                        openingBarClose = entryPrice,
                        isBuyEntry = isBuyEntry,
                        isStopEntry = isStopEntry,
                        scenario = entryScenario,
                        range = range
                    )
                }
            }
            EmulatorLog.bracketPlaced(
                symbol = symbol,
                orderIds = allOrderIds,
                entryPrice = entryPrice,
                initialMark = entryPrice,
                walkFloor = entryPrice,
                walkCeiling = entryPrice,
                entryScenario = entryScenario
            )
            onSymbolNeedsLiveQuotes(symbol)
        }
        emitTouchTurnBracketAck(
            symbol = symbol,
            symbolForAck = symbolForAck,
            allOrderIds = allOrderIds,
            adjustedPlan = adjustedPlan
        )
    }

    private fun emitTouchTurnBracketAck(
        symbol: String,
        symbolForAck: String,
        allOrderIds: List<Int>,
        adjustedPlan: TouchTurnOrderPlan
    ) {
        val tailResult = runCatching {
            publishPositions()
            finishEmulatedOpenOrdersLoad()
            val snapshot = openOrderBook.snapshot()
            val ack = TouchTurnBracketAck(
                symbol = symbol,
                orderIds = allOrderIds,
                result = Result.success(Unit),
                plan = adjustedPlan
            )
            emit(GatewayEvent.TouchTurnBracketPlaced(ack))
            EmulatorLog.bracketAckEmitted(
                symbol = symbol,
                orderIds = allOrderIds,
                success = true,
                openOrderCount = snapshot.size
            )
        }
        if (tailResult.isSuccess) return

        val error = tailResult.exceptionOrNull() ?: return
        EmulatorLog.bracketPublishTailFailed(symbol, error)
        if (allOrderIds.isEmpty()) {
            val failure = TouchTurnBracketAck(
                symbol = symbolForAck,
                orderIds = emptyList(),
                result = Result.failure(error),
                plan = adjustedPlan
            )
            emit(GatewayEvent.TouchTurnBracketPlaced(failure))
            EmulatorLog.bracketAckEmitted(
                symbol = symbol,
                orderIds = emptyList(),
                success = false,
                openOrderCount = 0,
                error = error.message
            )
            return
        }
        runCatching {
            val snapshot = openOrderBook.snapshot()
            val ack = TouchTurnBracketAck(
                symbol = symbol,
                orderIds = allOrderIds,
                result = Result.success(Unit),
                plan = adjustedPlan
            )
            emit(GatewayEvent.TouchTurnBracketPlaced(ack))
            EmulatorLog.bracketAckEmitted(
                symbol = symbol,
                orderIds = allOrderIds,
                success = true,
                openOrderCount = snapshot.size
            )
        }.onFailure { recoveryError ->
            EmulatorLog.bracketPublishTailFailed(symbol, recoveryError)
        }
    }

    /**
     * Pushes bid/ask/last from a real exchange feed (e.g. IB hybrid mode) into the fill book.
     * Ignored when [BrokerEmulatorConfig.pricingSource] is [EmulatorPricingSource.SYNTHETIC].
     * Fills are not evaluated until both bid and ask have been received at least once.
     */
    fun ingestExternalQuote(symbol: String, quote: LiveQuote, priorClose: Double?) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (config.pricingSource.isSynthetic) {
            EmulatorLog.externalQuoteIgnored(norm, "synthetic_mode")
            return
        }
        val merged = quoteBook.ingestExternal(norm, quote)
        if (merged == null) {
            EmulatorLog.externalQuoteIgnored(norm, "incomplete_bid_ask")
            return
        }
        if (quoteBook.canTriggerFills(norm) && externalFeedReadyLogged.add(norm)) {
            EmulatorLog.externalFeedReady(norm)
        }
        applyPriorClose(norm, priorClose)
        evaluateAndPublishAfterQuoteUpdate()
    }

    /** @see ingestExternalQuote */
    fun ingestLiveQuote(symbol: String, quote: LiveQuote, priorClose: Double?) =
        ingestExternalQuote(symbol, quote, priorClose)

    private fun applyPriorClose(norm: String, priorClose: Double?) {
        priorClose?.takeIf { it > 0.0 }?.let { close ->
            dynamicInstruments[norm]?.let { instrument ->
                dynamicInstruments[norm] = instrument.copy(priorClose = close)
            }
        }
    }

    private fun evaluateAndPublishAfterQuoteUpdate() {
        evaluateOrderFillsOnTick()
        refreshPositionMarks()
        publishPositions()
        publishOrders()
        publishQuotes()
    }

    /** Entry limit fills when bid/ask cross the limit (buy at ask, sell at bid). */
    private fun fillEntryImmediately(entryOrderId: Int) {
        val entry = orders[entryOrderId] ?: return
        if (entry.remaining <= 0 || entry.isTerminal()) return
        applyFill(entry, entry.remaining)
    }

    private fun pickTouchTurnEntryScenario(): TouchTurnEntryScenario {
        config.touchTurnEntryScenarioOverride?.let { return it }
        if (config.touchTurnEntryFillImmediately) return TouchTurnEntryScenario.IMMEDIATE
        return if (random.nextDouble() < config.touchTurnEntryNeverFillProbability) {
            TouchTurnEntryScenario.NEVER_FILL
        } else {
            TouchTurnEntryScenario.APPROACH_AND_FILL
        }
    }

    private fun isTouchTurnEntryFilled(symbol: String): Boolean {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return orders.values.any { order ->
            bracketManagedOrderIds.contains(order.orderId) &&
                order.parentId == 0 &&
                order.status == "Filled" &&
                SymbolMarkets.symbolsMatch(order.symbol, norm)
        }
    }

    private fun clearFilledEntryPending() {
        bracketEntryPending.keys.toList().forEach { symbol ->
            if (isTouchTurnEntryFilled(symbol)) {
                bracketEntryPending.remove(symbol)
            }
        }
    }

    suspend fun runMarketTick() {
        if (!shouldRunMarketTicks()) return
        evaluateOrderFillsOnTick()
        if (config.pricingSource.isSynthetic) {
            tickSymbolsForMarketData().forEach { symbol ->
                val pending = bracketEntryPending[symbol]
                when {
                    pending != null && !isTouchTurnEntryFilled(symbol) -> {
                        syntheticQuotes.advanceEntryApproach(symbol, pending)
                        pending.ticksElapsed++
                    }
                    shouldUseBracketPriceWalk(symbol) -> {
                        val walk = bracketPriceWalks[symbol] ?: return@forEach
                        syntheticQuotes.applyBracketWalk(
                            symbol,
                            walk,
                            advanceBracketPriceWalk(symbol, walk)
                        )
                    }
                    else -> {
                        bracketPriceWalks.remove(symbol)
                        if (quoteBook.quoteOrNull(symbol) != null) {
                            syntheticQuotes.applyBackgroundJitter(symbol)
                        }
                    }
                }
            }
            evaluateOrderFillsOnTick()
            clearFilledEntryPending()
        }
        refreshPositionMarks()
        publishPositions()
        publishOrders()
        publishQuotes()
    }

    suspend fun runOrderProgressStep() {
        if (!connected || !config.simulateOrderProgress) return
        val partial = orders.values.firstOrNull { it.filled > 0 && it.remaining > 0 && !it.isTerminal() }
        if (partial != null) {
            val fillQty = minOf(5, partial.remaining)
            updateOrder(
                partial.copy(
                    filled = partial.filled + fillQty,
                    remaining = partial.remaining - fillQty,
                    status = if (partial.remaining - fillQty <= 0) "Filled" else "Submitted"
                )
            )
            publishOrders()
            return
        }
        val open = orders.values.firstOrNull {
            it.filled == 0 && !it.isTerminal() && it.orderType == "LMT" &&
                !bracketManagedOrderIds.contains(it.orderId)
        }
        if (open != null && random.nextDouble() < 0.35) {
            val fillQty = minOf(10, open.remaining)
            applyFill(open, fillQty)
        }
    }

    private fun seedBooks() {
        if (config.pricingSource.isSynthetic) {
            quoteBook.clear()
        }
        positions = mutableListOf()
        orders = mutableMapOf()
    }

    private fun seedSymbolQuote(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val instrument = resolveInstrument(norm) ?: return
        val ref = instrument.referencePrice
        val spread = EmulatorMarketQuoteBook.spreadForBracketRange(
            range = ref * 0.02,
            referencePrice = ref,
            spreadPctOfRange = config.emulatorQuoteSpreadPctOfRange
        )
        quoteBook.seedSymbol(
            norm,
            EmulatorMarketQuote(
                last = ref,
                bid = ref - spread / 2.0,
                ask = ref + spread / 2.0,
                halfSpread = spread / 2.0
            )
        )
    }

    private fun hasActiveStreamingSubscriptions(): Boolean =
        streamSubscriptionRefCount.values.any { it > 0 }

    private fun incrementStreamRefCount(symbol: String): Boolean {
        val next = (streamSubscriptionRefCount[symbol] ?: 0) + 1
        streamSubscriptionRefCount[symbol] = next
        return next == 1
    }

    private fun decrementStreamRefCount(symbol: String): Boolean {
        val current = streamSubscriptionRefCount[symbol] ?: return false
        if (current <= 1) {
            streamSubscriptionRefCount.remove(symbol)
            return true
        }
        streamSubscriptionRefCount[symbol] = current - 1
        return false
    }

    private fun allocateOrderId(): Int = nextOrderId++

    private fun resolveInstrument(symbol: String): EmulatorInstrument? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return catalog[norm] ?: dynamicInstruments[norm]
    }

    private fun ensureInstrument(
        symbol: String,
        currency: String,
        referencePrice: Double,
        identity: daytrader.domain.InstrumentIdentity? = null
    ): EmulatorInstrument {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        resolveInstrument(norm)?.let { existing ->
            val primaryExch = identity?.primaryExch?.takeIf { it.isNotBlank() }
            if (primaryExch != null && existing.primaryExch.isNullOrBlank()) {
                val updated = existing.copy(primaryExch = primaryExch)
                dynamicInstruments[norm] = updated
                return updated
            }
            return existing
        }
        return EmulatorInstrument(
            symbol = norm,
            companyName = norm,
            currency = currency,
            priorClose = referencePrice,
            referencePrice = referencePrice,
            primaryExch = identity?.primaryExch?.takeIf { it.isNotBlank() }
        ).also { dynamicInstruments[norm] = it }
    }

    private fun shouldUseBracketPriceWalk(symbol: String): Boolean {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val entryFilled = orders.values.any { order ->
            bracketManagedOrderIds.contains(order.orderId) &&
                order.parentId == 0 &&
                order.status == "Filled" &&
                SymbolMarkets.symbolsMatch(order.symbol, norm)
        }
        if (!entryFilled) return false
        val hasActiveBracketExits = orders.values.any { order ->
            bracketManagedOrderIds.contains(order.orderId) &&
                order.parentId != 0 &&
                !order.isTerminal() &&
                SymbolMarkets.symbolsMatch(order.symbol, norm)
        }
        val hasPosition = positions.any {
            SymbolMarkets.symbolsMatch(it.instrument.symbol, norm) && it.quantity != 0
        }
        return hasActiveBracketExits && hasPosition
    }

    private fun pickBracketExitTarget(): BracketExitTarget =
        if (random.nextDouble() < config.bracketExitTakeProfitProbability) {
            BracketExitTarget.TAKE_PROFIT
        } else {
            BracketExitTarget.STOP_LOSS
        }

    private fun directionTowardTarget(towardTakeProfit: Int, target: BracketExitTarget): Int =
        when (target) {
            BracketExitTarget.TAKE_PROFIT -> towardTakeProfit
            BracketExitTarget.STOP_LOSS -> -towardTakeProfit
        }

    private fun walkDirection(walk: BracketPriceWalk): Int {
        val towardTarget = directionTowardTarget(walk.towardTakeProfitDirection, walk.targetExit)
        return if (random.nextDouble() < config.bracketWalkSteerTowardTargetProbability) {
            towardTarget
        } else {
            -towardTarget
        }
    }

    private fun advanceBracketPriceWalk(symbol: String, walk: BracketPriceWalk): Double {
        val range = walk.ceiling - walk.floor
        if (range <= 0.0) return quoteFor(symbol).aggressivePrice(walk.isLongPosition)
        val quote = quoteFor(symbol)
        val current = quote.aggressivePrice(walk.isLongPosition)
        val step = range * config.bracketWalkStepPctOfRange * (0.5 + random.nextDouble())
        var next = current + walk.direction * step
        if (next >= walk.ceiling) {
            next = walk.ceiling
            walk.direction = bounceDirectionFromBoundary(walk, hitTakeProfitSide = walk.takeProfitPrice >= walk.ceiling - 1e-9)
        } else if (next <= walk.floor) {
            next = walk.floor
            walk.direction = bounceDirectionFromBoundary(walk, hitTakeProfitSide = walk.takeProfitPrice <= walk.floor + 1e-9)
        }
        if (random.nextDouble() < config.bracketWalkDirectionFlipChance) {
            walk.direction = walkDirection(walk)
        }
        return next.coerceIn(walk.floor, walk.ceiling)
    }

    /** After touching a bracket bound, steer back toward the pre-selected exit target. */
    private fun bounceDirectionFromBoundary(walk: BracketPriceWalk, hitTakeProfitSide: Boolean): Int {
        val towardTarget = directionTowardTarget(walk.towardTakeProfitDirection, walk.targetExit)
        val hitTargetSide = when (walk.targetExit) {
            BracketExitTarget.TAKE_PROFIT -> hitTakeProfitSide
            BracketExitTarget.STOP_LOSS -> !hitTakeProfitSide
        }
        return if (hitTargetSide) -towardTarget else towardTarget
    }

    private fun tickSymbolsForMarketData(): Set<String> =
        if (config.pricingSource.isSynthetic) {
            streamSubscriptionRefCount.filterValues { it > 0 }.keys.toSet()
        } else {
            (catalog.keys + dynamicInstruments.keys + orders.values.map { SymbolMarkets.normalizeSymbol(it.symbol) })
                .toSet()
        }

    private fun evaluateOrderFillsOnTick() {
        updateTrailingStops()
        orders.values.toList().forEach { order ->
            if (!bracketManagedOrderIds.contains(order.orderId)) return@forEach
            if (order.isTerminal() || order.remaining <= 0 || !isOrderActiveForFill(order)) return@forEach
            when (order.orderType) {
                "LMT" -> maybeFillLimitOrder(order)
                "STP", "TRAIL" -> maybeFillStopOrder(order)
            }
        }
    }

    private fun updateTrailingStops() {
        orders.values.forEach { order ->
            if (!bracketManagedOrderIds.contains(order.orderId)) return@forEach
            if (order.trailTriggerPrice == null || order.trailArmStopPrice == null) return@forEach
            if (order.isTerminal() || order.remaining <= 0 || !isOrderActiveForFill(order)) return@forEach
            val updated = advanceTrailingStop(order) ?: return@forEach
            if (updated == order) return@forEach
            updateOrder(updated)
            syncOrderToOpenBook(order.orderId)
            publishOrders()
        }
    }

    private fun advanceTrailingStop(order: EmulatorOrder): EmulatorOrder? {
        val trigger = order.trailTriggerPrice ?: return null
        val quote = quoteBook.quoteOrNull(order.symbol) ?: return null
        if (!order.trailingArmed) {
            val crossed = when (order.action.uppercase()) {
                "SELL" -> quote.bid >= trigger
                "BUY" -> quote.ask <= trigger
                else -> false
            }
            if (!crossed) return order
            val armStop = order.trailArmStopPrice ?: return order
            val armReference = when (order.action.uppercase()) {
                "SELL" -> quote.bid
                "BUY" -> quote.ask
                else -> return order
            }
            return order.copy(
                trailingArmed = true,
                orderType = "TRAIL",
                trailAnchorPrice = armReference,
                trailExtremePrice = armReference,
                stopPrice = armStop
            )
        }
        val armReference = order.trailAnchorPrice ?: return order
        val entry = order.trailArmStopPrice ?: return order
        return when (order.action.uppercase()) {
            "SELL" -> {
                val extreme = maxOf(order.trailExtremePrice ?: armReference, quote.bid)
                val progress = extreme - armReference
                val candidateStop = entry + progress
                val newStop = maxOf(order.stopPrice ?: entry, candidateStop)
                if (newStop == order.stopPrice && extreme == order.trailExtremePrice) return order
                order.copy(trailExtremePrice = extreme, stopPrice = newStop)
            }
            "BUY" -> {
                val extreme = minOf(order.trailExtremePrice ?: armReference, quote.ask)
                val progress = armReference - extreme
                val candidateStop = entry - progress
                val newStop = minOf(order.stopPrice ?: entry, candidateStop)
                if (newStop == order.stopPrice && extreme == order.trailExtremePrice) return order
                order.copy(trailExtremePrice = extreme, stopPrice = newStop)
            }
            else -> order
        }
    }

    private fun isOrderActiveForFill(order: EmulatorOrder): Boolean {
        if (order.status == "PreSubmitted") return false
        if (order.parentId == 0) return true
        val parent = orders[order.parentId] ?: return false
        return parent.status == "Filled"
    }

    private fun maybeFillLimitOrder(order: EmulatorOrder) {
        if (!quoteBook.canTriggerFills(order.symbol)) return
        val quote = quoteBook.quoteOrNull(order.symbol) ?: return
        val limit = order.limitPrice ?: return
        val shouldFill = when (order.action.uppercase()) {
            "BUY" -> EmulatorMarketQuoteBook.buyLimitFillable(quote.ask, limit)
            "SELL" -> EmulatorMarketQuoteBook.sellLimitFillable(quote.bid, limit)
            else -> false
        }
        if (shouldFill) {
            applyFill(order, order.remaining)
        }
    }

    private fun maybeFillStopOrder(order: EmulatorOrder) {
        if (!quoteBook.canTriggerFills(order.symbol)) return
        val quote = quoteBook.quoteOrNull(order.symbol) ?: return
        val stopPx = order.stopPrice ?: return
        val triggered = when (order.action.uppercase()) {
            "SELL" -> EmulatorMarketQuoteBook.sellStopTriggered(quote.bid, stopPx)
            "BUY" -> EmulatorMarketQuoteBook.buyStopTriggered(quote.ask, stopPx)
            else -> false
        }
        if (triggered) {
            applyFill(order, order.remaining)
        }
    }

    private fun plannedToEmulatorOrder(
        orderId: Int,
        planned: TouchTurnPlannedOrder,
        symbol: String,
        currency: String,
        parentId: Int,
        status: String
    ): EmulatorOrder = EmulatorOrder(
        orderId = orderId,
        symbol = symbol,
        action = planned.action,
        quantity = planned.quantity,
        filled = 0,
        remaining = planned.quantity,
        orderType = planned.orderType,
        limitPrice = planned.price.takeIf { planned.orderType == "LMT" },
        stopPrice = planned.price.takeIf { planned.orderType == "STP" },
        status = status,
        currency = currency,
        parentId = parentId,
        trailTriggerPrice = planned.trailTriggerPrice,
        trailArmStopPrice = planned.trailArmStopPrice
    )

    private fun refreshPositionMarks() {
        positions = positions.map { pos ->
            val sym = pos.instrument.symbol
            val mkt = quoteMid(sym) ?: pos.marketPrice
            pos.copy(marketPrice = mkt)
        }.toMutableList()
    }

    private fun publishPositions() {
        val snapshot = positions.map { pos ->
            val q = quoteBook.quoteOrNull(pos.instrument.symbol)
            if (q != null) {
                pos.toAccountPosition(bid = q.bid, ask = q.ask, last = q.last)
            } else {
                pos.toAccountPosition()
            }
        }.sortedBy { it.symbol }
        emit(GatewayEvent.PositionsSnapshot(snapshot))
    }

    private fun publishQuotes() {
        val snapshot = quoteBook.toLiveQuoteSnapshot()
        EmulatorPriceLog.recordSnapshot(snapshot, config.pricingSource.name)
        emit(GatewayEvent.QuotesSnapshot(snapshot))
    }

    /** Mirrors IB [openOrder] — one working order reported, then a snapshot publish. */
    private fun onEmulatedOpenOrder(order: EmulatorOrder) {
        orders[order.orderId] = order
        openOrderBook.applyOpenOrder(order.toWorkingOrder())
        publishOrders()
    }

    /** Mirrors IB [openOrderEnd] — final snapshot after a batch of open-order callbacks. */
    private fun finishEmulatedOpenOrdersLoad() {
        orders.values
            .filter { !it.isTerminal() }
            .forEach { openOrderBook.applyOpenOrder(it.toWorkingOrder()) }
        publishOrders()
    }

    private fun syncOrderToOpenBook(orderId: Int) {
        val order = orders[orderId] ?: run {
            openOrderBook.removeOrder(orderId)
            return
        }
        if (order.isTerminal() || order.remaining <= 0) {
            openOrderBook.removeOrder(orderId)
        } else {
            openOrderBook.applyOpenOrder(order.toWorkingOrder())
        }
    }

    private fun publishOrders() {
        val snapshot = openOrderBook.snapshot()
        val fingerprint = snapshot.joinToString("|") { "${it.orderId}:${it.status}:${it.remaining}" }
        emit(GatewayEvent.OpenOrdersSnapshot(snapshot))
        if (fingerprint != lastPublishedOpenOrdersFingerprint) {
            lastPublishedOpenOrdersFingerprint = fingerprint
            val symbolSummary = snapshot.groupBy { it.symbol }.entries.joinToString(";") { (sym, orders) ->
                "$sym=${orders.size}"
            }
            EmulatorLog.openOrdersPublished(snapshot.size, symbolSummary.ifEmpty { "none" })
        }
    }

    private fun updateOrder(order: EmulatorOrder) {
        when {
            order.status == "Cancelled" || order.status == "Inactive" || order.status == "ApiCancelled" -> {
                orders.remove(order.orderId)
                bracketManagedOrderIds.remove(order.orderId)
            }
            order.remaining <= 0 -> {
                orders[order.orderId] = order.copy(
                    status = "Filled",
                    remaining = 0,
                    filled = order.quantity
                )
            }
            else -> orders[order.orderId] = order
        }
        syncOrderToOpenBook(order.orderId)
    }

    private fun applyFill(order: EmulatorOrder, fillQty: Int) {
        if (fillQty <= 0) return
        val current = orders[order.orderId] ?: order
        if (current.isTerminal() || current.remaining <= 0) return
        val effectiveQty = minOf(fillQty, current.remaining)
        val fillPrice = resolveFillPrice(current)
        val positionBefore = positions.find {
            SymbolMarkets.symbolsMatch(it.instrument.symbol, current.symbol)
        }
        val remaining = current.remaining - effectiveQty
        val filled = current.filled + effectiveQty
        val updated = current.copy(
            filled = filled,
            remaining = remaining,
            status = if (remaining <= 0) "Filled" else "Submitted"
        )
        updateOrder(updated)
        adjustPositionForFill(current, effectiveQty, fillPrice)
        val realizedPnL = computeFillRealizedPnL(positionBefore, current, effectiveQty, fillPrice)
        if (remaining <= 0 && current.parentId == 0) {
            val norm = SymbolMarkets.normalizeSymbol(current.symbol)
            activateChildOrders(current.orderId)
            pendingBracketWalks.remove(norm)?.let { walk ->
                bracketPriceWalks[norm] = walk
                EmulatorLog.bracketExitWalkStarted(current.symbol, walk.floor, walk.ceiling)
            }
        }
        if (remaining <= 0 && current.parentId != 0) {
            cancelSiblingBracketOrders(current.parentId, filledOrderId = current.orderId)
            bracketPriceWalks.remove(SymbolMarkets.normalizeSymbol(current.symbol))
        }
        val positionQty = positions.find {
            SymbolMarkets.symbolsMatch(it.instrument.symbol, current.symbol)
        }?.quantity ?: 0
        recordFill(current, effectiveQty, realizedPnL, positionQty, fillPrice)
        refreshPositionMarks()
        publishPositions()
        publishOrders()
        publishQuotes()
    }

    private fun computeFillRealizedPnL(
        positionBefore: EmulatorPosition?,
        order: EmulatorOrder,
        fillQty: Int,
        fillPrice: Double
    ): Double? {
        val pos = positionBefore ?: return null
        if (pos.quantity == 0) return null
        val signedFill = when (order.action.uppercase()) {
            "BUY" -> fillQty
            "SELL" -> -fillQty
            else -> return null
        }
        val isClosing = (pos.quantity > 0 && signedFill < 0) || (pos.quantity < 0 && signedFill > 0)
        if (!isClosing) return null
        val exitPrice = fillPrice
        val closeQty = minOf(kotlin.math.abs(signedFill), kotlin.math.abs(pos.quantity))
        return InstrumentPriceScale.realizedPnLOnClose(
            closeQty = closeQty,
            avgPriceRaw = pos.avgPrice,
            exitPriceRaw = exitPrice,
            currency = order.currency,
            isLong = pos.quantity > 0,
            primaryExch = pos.instrument.primaryExch
        )
    }

    private fun recordFill(
        order: EmulatorOrder,
        fillQty: Int,
        realizedPnL: Double?,
        positionQtyAfter: Int,
        fillPrice: Double
    ) {
        val execId = "emu-${order.orderId}-${sessionFills.size}"
        val fill = BrokerFill(
            execId = execId,
            orderId = order.orderId,
            permId = order.orderId.toLong(),
            parentOrderId = order.parentId,
            symbol = order.symbol,
            side = order.action,
            quantity = fillQty,
            price = fillPrice,
            time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            currency = order.currency,
            commission = 0.0,
            realizedPnL = realizedPnL
        )
        sessionFills.add(fill)
        EmulatorLog.orderFilled(
            symbol = order.symbol,
            orderId = order.orderId,
            qty = fillQty,
            price = fillPrice,
            positionQty = positionQtyAfter,
            execId = execId,
            parentOrderId = order.parentId.takeIf { it != 0 },
            side = order.action,
            realizedPnL = realizedPnL
        )
        publishFills()
    }

    fun republishFills() {
        publishFills()
    }

    fun cancelOpenOrdersForSymbol(symbol: String) {
        if (!connected) return
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        var cancelled = 0
        orders.entries.toList().forEach { (_, order) ->
            if (SymbolMarkets.symbolsMatch(norm, order.symbol) && !order.isTerminal()) {
                updateOrder(order.copy(status = "Cancelled"))
                cancelled++
            }
        }
        bracketPriceWalks.remove(norm)
        pendingBracketWalks.remove(norm)
        bracketEntryPending.remove(norm)
        if (cancelled > 0) {
            EmulatorLog.sessionOrdersCancelled(norm, cancelled)
        }
        publishOrders()
    }

    fun flattenSymbolForSymbol(symbol: String) {
        cancelOpenOrdersForSymbol(symbol)
        closeOpenPositionForSymbol(symbol)
        publishPositions()
        publishOrders()
    }

    fun closeOpenPositionForSymbol(symbol: String) {
        if (!connected) return
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val index = positions.indexOfFirst {
            SymbolMarkets.symbolsMatch(norm, it.instrument.symbol) && it.quantity != 0
        }
        if (index < 0) return
        val pos = positions[index]
        val closeQty = kotlin.math.abs(pos.quantity)
        val mark = quoteMid(norm)?.takeIf { it > 0.0 } ?: pos.marketPrice
        val closeAction = if (pos.quantity > 0) "SELL" else "BUY"
        val closeOrder = EmulatorOrder(
            orderId = allocateOrderId(),
            symbol = norm,
            action = closeAction,
            quantity = closeQty,
            filled = 0,
            remaining = closeQty,
            orderType = "MKT",
            limitPrice = mark,
            stopPrice = null,
            status = "Submitted",
            currency = pos.instrument.currency,
            parentId = 0
        )
        applyFill(closeOrder, closeQty)
        EmulatorLog.sessionPositionClosed(norm, closeAction, closeQty, mark)
        publishPositions()
        publishOrders()
    }

    private fun publishFills() {
        emit(GatewayEvent.FillsSnapshot(sessionFills.toList()))
    }

    /**
     * Resolves the trade price at fill time. Limits and stops are barriers only; the fill uses the
     * aggressive live quote (buy at ask, sell at bid) when a book is available.
     */
    private fun resolveFillPrice(order: EmulatorOrder): Double {
        val norm = SymbolMarkets.normalizeSymbol(order.symbol)
        val quote = quoteBook.quoteOrNull(norm)
        if (quote != null) {
            when (order.orderType) {
                "LMT" -> {
                    val limit = order.limitPrice
                    if (limit != null) {
                        EmulatorMarketQuoteBook.limitFillPrice(order.action, quote.bid, quote.ask, limit)
                            ?.let { return it }
                    }
                }
                "STP", "TRAIL" -> {
                    return EmulatorMarketQuoteBook.aggressiveFillPrice(order.action, quote.bid, quote.ask)
                }
                "MKT" -> {
                    return EmulatorMarketQuoteBook.aggressiveFillPrice(order.action, quote.bid, quote.ask)
                }
            }
        }
        return order.limitPrice ?: order.stopPrice ?: quoteMid(norm) ?: 0.0
    }

    private fun cancelSiblingBracketOrders(parentOrderId: Int, filledOrderId: Int) {
        val cancelled = mutableListOf<Int>()
        val symbol = orders[filledOrderId]?.symbol
        orders.entries.toList().forEach { (id, order) ->
            if (order.parentId == parentOrderId && id != filledOrderId && !order.isTerminal()) {
                cancelled += id
                updateOrder(order.copy(status = "Cancelled"))
            }
        }
        if (cancelled.isNotEmpty() && symbol != null) {
            EmulatorLog.bracketSiblingCancelled(symbol, filledOrderId, cancelled)
        }
        publishOrders()
    }

    private fun activateChildOrders(parentOrderId: Int) {
        val activated = mutableListOf<Int>()
        val symbol = orders[parentOrderId]?.symbol
        orders.entries.toList().forEach { (id, order) ->
            if (order.parentId == parentOrderId && order.status == "PreSubmitted") {
                activated += id
                orders[id] = order.copy(status = "Submitted")
            }
        }
        if (activated.isNotEmpty() && symbol != null) {
            EmulatorLog.bracketChildrenActivated(symbol, parentOrderId, activated)
        }
    }

    private fun adjustPositionForFill(order: EmulatorOrder, fillQty: Int, fillPrice: Double) {
        val instrument = resolveInstrument(order.symbol) ?: return
        val signedQty = when (order.action.uppercase()) {
            "BUY" -> fillQty
            "SELL" -> -fillQty
            else -> 0
        }
        if (signedQty == 0) return
        val price = fillPrice.takeIf { it > 0.0 }
            ?: quoteMid(order.symbol)
            ?: instrument.referencePrice
        val existing = positions.indexOfFirst { it.instrument.symbol == instrument.symbol }
        if (existing >= 0) {
            val pos = positions[existing]
            val newQty = pos.quantity + signedQty
            if (newQty == 0) {
                positions.removeAt(existing)
            } else {
                val newAvg = weightedAvg(pos.avgPrice, pos.quantity, price, signedQty, newQty)
                positions[existing] = pos.copy(
                    quantity = newQty,
                    avgPrice = newAvg,
                    marketPrice = quoteMid(instrument.symbol) ?: price
                )
            }
        } else {
            positions.add(
                EmulatorPosition(
                    account = config.accountId,
                    instrument = instrument,
                    quantity = signedQty,
                    avgPrice = price,
                    marketPrice = quoteMid(instrument.symbol) ?: price
                )
            )
        }
    }

    private fun weightedAvg(
        avg1: Double,
        qty1: Int,
        price2: Double,
        qty2: Int,
        newQty: Int
    ): Double {
        if (newQty == 0) return avg1
        val cost1 = avg1 * kotlin.math.abs(qty1)
        val cost2 = price2 * kotlin.math.abs(qty2)
        return (cost1 + cost2) / kotlin.math.abs(newQty)
    }

    private fun startMarketSimulation() {
        ticksRunning = true
    }

    private fun startOrderSimulation() {
        orderSimRunning = config.simulateOrderProgress
    }

    fun shouldRunMarketTicks(): Boolean = when {
        !connected || !ticksRunning -> false
        config.pricingSource.isSynthetic -> hasActiveStreamingSubscriptions()
        else -> true
    }

    fun shouldRunOrderSim(): Boolean = connected && orderSimRunning && config.simulateOrderProgress

    private fun quoteFor(symbol: String): EmulatorMarketQuote {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return quoteBook.quoteFor(norm) {
            val ref = resolveInstrument(norm)?.referencePrice ?: 100.0
            val spread = EmulatorMarketQuoteBook.spreadForBracketRange(
                range = ref * 0.02,
                referencePrice = ref,
                spreadPctOfRange = config.emulatorQuoteSpreadPctOfRange
            )
            EmulatorMarketQuote(
                last = ref,
                bid = ref - spread / 2.0,
                ask = ref + spread / 2.0,
                halfSpread = spread / 2.0
            )
        }
    }

    private fun quoteMid(symbol: String): Double? = quoteBook.mid(symbol)

    private fun setQuoteMid(symbol: String, mid: Double) {
        quoteFor(symbol).setMid(mid)
    }
}
