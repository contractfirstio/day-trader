package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * In-memory brokerage simulation: connection lifecycle, positions, working orders,
 * streaming marks, and historical data responses.
 */
class BrokerEmulatorEngine(
    private val config: BrokerEmulatorConfig = BrokerEmulatorConfig.Default,
    private val emit: (GatewayEvent) -> Unit,
    private val random: Random = Random(42)
) {
    private val catalog = EmulatorSeedCatalog.instruments()
    private val livePrices = catalog.mapValues { (_, instrument) -> instrument.referencePrice }.toMutableMap()
    private var positions = mutableListOf<EmulatorPosition>()
    private var orders = mutableMapOf<Int, EmulatorOrder>()
    private var nextOrderId = 1_000
    private var connected = false
    private var ticksRunning = false
    private var orderSimRunning = false
    private val dynamicInstruments = mutableMapOf<String, EmulatorInstrument>()
    private val bracketManagedOrderIds = mutableSetOf<Int>()

    fun handleConnect() {
        if (connected) return
        emit(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Connecting))
    }

    suspend fun finishConnect() {
        delay(config.connectDelayMs)
        seedBooks()
        connected = true
        emit(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Connected))
        publishPositions()
        publishOrders()
        startMarketSimulation()
        startOrderSimulation()
    }

    fun handleDisconnect() {
        connected = false
        ticksRunning = false
        orderSimRunning = false
        positions.clear()
        orders.clear()
        bracketManagedOrderIds.clear()
        dynamicInstruments.clear()
        emit(GatewayEvent.PositionsSnapshot(emptyList()))
        emit(GatewayEvent.OpenOrdersSnapshot(emptyList()))
        emit(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Disconnected))
    }

    suspend fun handleReconnect() {
        handleDisconnect()
        delay(config.reconnectDelayMs)
        handleConnect()
        finishConnect()
    }

    fun handleShutdown() {
        handleDisconnect()
    }

    suspend fun fetchFirstFifteenMinuteCandle(requestId: Long, symbol: String) {
        delay(config.historicalDelayMs)
        val trimmed = symbol.trim().uppercase()
        val instrument = resolveInstrument(trimmed)
        val result = if (instrument == null) {
            Result.failure(IllegalArgumentException("Unknown symbol: $symbol"))
        } else {
            val candleResult = EmulatorHistoricalData.firstFifteenMinuteCandle(
                symbol = trimmed,
                instrument = instrument,
                config = config
            )
            candleResult.onSuccess { bar ->
                config.firstCandleSecondsUntilClose?.let { seconds ->
                    EmulatorLog.firstCandleScheduled(trimmed, bar.time.orEmpty(), seconds)
                }
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
            Result.failure(IllegalArgumentException("Unknown symbol: $symbol"))
        } else {
            EmulatorHistoricalData.fourteenDayAdr(trimmed, instrument)
        }
        emit(GatewayEvent.FourteenDayAdrReady(requestId, result))
    }

    fun placeTouchTurnBracket(plan: TouchTurnOrderPlan) {
        if (!connected) return
        val symbol = SymbolMarkets.normalizeSymbol(plan.symbol)
        val entryLeg = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY } ?: return
        val entryPrice = entryLeg.price

        orders.entries.removeIf { (_, order) ->
            SymbolMarkets.symbolsMatch(order.symbol, symbol) && !order.isTerminal()
        }
        bracketManagedOrderIds.removeIf { id -> orders[id]?.let { SymbolMarkets.symbolsMatch(it.symbol, symbol) } == true }

        ensureInstrument(symbol, plan.currencyCode, entryPrice)
        livePrices[symbol] = entryPrice

        val entryId = allocateOrderId()
        bracketManagedOrderIds.add(entryId)
        orders[entryId] = plannedToEmulatorOrder(
            orderId = entryId,
            planned = entryLeg,
            symbol = symbol,
            currency = plan.currencyCode,
            parentId = 0,
            status = "Submitted"
        )

        val childIds = mutableListOf<Int>()
        plan.orders.filter { it.role != TouchTurnOrderRole.ENTRY }.forEach { leg ->
            val childId = allocateOrderId()
            childIds.add(childId)
            bracketManagedOrderIds.add(childId)
            orders[childId] = plannedToEmulatorOrder(
                orderId = childId,
                planned = leg,
                symbol = symbol,
                currency = plan.currencyCode,
                parentId = entryId,
                status = "PreSubmitted"
            )
        }

        EmulatorLog.bracketPlaced(symbol, listOf(entryId) + childIds, entryPrice)
        publishOrders()
    }

    suspend fun runMarketTick() {
        if (!connected || !ticksRunning) return
        tickSymbolsForMarketData().forEach { symbol ->
            val current = livePrices[symbol] ?: return@forEach
            val jitter = 1.0 + random.nextDouble(-config.marketTickJitterPct, config.marketTickJitterPct)
            livePrices[symbol] = (current * jitter).coerceAtLeast(0.01)
        }
        evaluateOrderFillsOnTick()
        refreshPositionMarks()
        publishPositions()
        publishOrders()
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
        positions = mutableListOf()
        val orderList = EmulatorSeedCatalog.initialOrders(catalog, livePrices) { allocateOrderId() }
        orders = orderList.associateBy { it.orderId }.toMutableMap()
    }

    private fun allocateOrderId(): Int = nextOrderId++

    private fun resolveInstrument(symbol: String): EmulatorInstrument? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return catalog[norm] ?: dynamicInstruments[norm]
    }

    private fun ensureInstrument(symbol: String, currency: String, referencePrice: Double): EmulatorInstrument {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        resolveInstrument(norm)?.let { return it }
        return EmulatorInstrument(
            symbol = norm,
            companyName = norm,
            currency = currency,
            priorClose = referencePrice,
            referencePrice = referencePrice
        ).also { dynamicInstruments[norm] = it }
    }

    private fun tickSymbolsForMarketData(): Set<String> =
        (catalog.keys + dynamicInstruments.keys + orders.values.map { SymbolMarkets.normalizeSymbol(it.symbol) })
            .toSet()

    private fun evaluateOrderFillsOnTick() {
        orders.values.toList().forEach { order ->
            if (!bracketManagedOrderIds.contains(order.orderId)) return@forEach
            if (order.isTerminal() || order.remaining <= 0 || !isOrderActiveForFill(order)) return@forEach
            when (order.orderType) {
                "LMT" -> maybeFillLimitOrder(order)
                "STP" -> maybeFillStopOrder(order)
            }
        }
    }

    private fun isOrderActiveForFill(order: EmulatorOrder): Boolean {
        if (order.parentId == 0) return true
        val parent = orders[order.parentId] ?: return false
        return parent.status == "Filled"
    }

    private fun maybeFillLimitOrder(order: EmulatorOrder) {
        val mkt = livePrices[SymbolMarkets.normalizeSymbol(order.symbol)] ?: return
        val limit = order.limitPrice ?: return
        val shouldFill = when (order.action.uppercase()) {
            "BUY" -> mkt <= limit
            "SELL" -> mkt >= limit
            else -> false
        }
        if (shouldFill) {
            applyFill(order, order.remaining)
        }
    }

    private fun maybeFillStopOrder(order: EmulatorOrder) {
        val mkt = livePrices[SymbolMarkets.normalizeSymbol(order.symbol)] ?: return
        val stopPx = order.stopPrice ?: return
        val triggered = when (order.action.uppercase()) {
            "SELL" -> mkt <= stopPx
            "BUY" -> mkt >= stopPx
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
        parentId = parentId
    )

    private fun refreshPositionMarks() {
        positions = positions.map { pos ->
            val mkt = livePrices[pos.instrument.symbol] ?: pos.marketPrice
            pos.copy(marketPrice = mkt)
        }.toMutableList()
    }

    private fun publishPositions() {
        val snapshot = positions.map { it.toAccountPosition() }.sortedBy { it.symbol }
        emit(GatewayEvent.PositionsSnapshot(snapshot))
    }

    private fun publishOrders() {
        val snapshot = orders.values
            .filter { !it.isTerminal() }
            .sortedBy { it.orderId }
            .map { it.toWorkingOrder() }
        emit(GatewayEvent.OpenOrdersSnapshot(snapshot))
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
    }

    private fun applyFill(order: EmulatorOrder, fillQty: Int) {
        if (fillQty <= 0) return
        val remaining = order.remaining - fillQty
        val filled = order.filled + fillQty
        val updated = order.copy(
            filled = filled,
            remaining = remaining,
            status = if (remaining <= 0) "Filled" else "Submitted"
        )
        updateOrder(updated)
        adjustPositionForFill(order, fillQty)
        if (remaining <= 0 && order.parentId == 0) {
            activateChildOrders(order.orderId)
        }
        val positionQty = positions.find {
            SymbolMarkets.symbolsMatch(it.instrument.symbol, order.symbol)
        }?.quantity ?: 0
        EmulatorLog.orderFilled(order.symbol, order.orderId, fillQty, order.fillPrice(), positionQty)
    }

    private fun EmulatorOrder.fillPrice(): Double =
        limitPrice ?: stopPrice ?: livePrices[SymbolMarkets.normalizeSymbol(symbol)] ?: 0.0

    private fun activateChildOrders(parentOrderId: Int) {
        orders.entries.forEach { (id, order) ->
            if (order.parentId == parentOrderId && order.status == "PreSubmitted") {
                orders[id] = order.copy(status = "Submitted")
            }
        }
    }

    private fun adjustPositionForFill(order: EmulatorOrder, fillQty: Int) {
        val instrument = resolveInstrument(order.symbol) ?: return
        val signedQty = when (order.action.uppercase()) {
            "BUY" -> fillQty
            "SELL" -> -fillQty
            else -> 0
        }
        if (signedQty == 0) return
        val price = order.limitPrice ?: livePrices[order.symbol] ?: instrument.referencePrice
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
                    marketPrice = livePrices[instrument.symbol] ?: price
                )
            }
        } else {
            positions.add(
                EmulatorPosition(
                    account = config.accountId,
                    instrument = instrument,
                    quantity = signedQty,
                    avgPrice = price,
                    marketPrice = livePrices[instrument.symbol] ?: price
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

    fun shouldRunMarketTicks(): Boolean = connected && ticksRunning

    fun shouldRunOrderSim(): Boolean = connected && orderSimRunning && config.simulateOrderProgress
}
