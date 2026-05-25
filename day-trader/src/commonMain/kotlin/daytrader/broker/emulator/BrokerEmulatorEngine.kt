package daytrader.broker.emulator

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
            EmulatorHistoricalData.firstFifteenMinuteCandle(trimmed, instrument)
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

    suspend fun runMarketTick() {
        if (!connected || !ticksRunning) return
        catalog.keys.forEach { symbol ->
            val current = livePrices[symbol] ?: return@forEach
            val jitter = 1.0 + random.nextDouble(-config.marketTickJitterPct, config.marketTickJitterPct)
            livePrices[symbol] = (current * jitter).coerceAtLeast(0.01)
        }
        refreshPositionMarks()
        publishPositions()
        maybeTriggerStopLimitFills()
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
        val open = orders.values.firstOrNull { it.filled == 0 && !it.isTerminal() && it.orderType == "LMT" }
        if (open != null && random.nextDouble() < 0.35) {
            val fillQty = minOf(10, open.remaining)
            applyFill(open, fillQty)
        }
    }

    private fun seedBooks() {
        positions = EmulatorSeedCatalog.initialPositions(config.accountId, livePrices).toMutableList()
        val orderList = EmulatorSeedCatalog.initialOrders(catalog, livePrices) { allocateOrderId() }
        orders = orderList.associateBy { it.orderId }.toMutableMap()
    }

    private fun allocateOrderId(): Int = nextOrderId++

    private fun resolveInstrument(symbol: String): EmulatorInstrument? {
        catalog[symbol]?.let { return it }
        val norm = daytrader.broker.SymbolMarkets.normalizeSymbol(symbol)
        return catalog[norm]
    }

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
        if (order.isTerminal() || order.remaining <= 0) {
            orders.remove(order.orderId)
        } else {
            orders[order.orderId] = order
        }
    }

    private fun applyFill(order: EmulatorOrder, fillQty: Int) {
        val remaining = order.remaining - fillQty
        val filled = order.filled + fillQty
        val updated = order.copy(
            filled = filled,
            remaining = remaining,
            status = if (remaining <= 0) "Filled" else "Submitted"
        )
        updateOrder(updated)
        adjustPositionForFill(order, fillQty)
        publishPositions()
        publishOrders()
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

    private fun maybeTriggerStopLimitFills() {
        orders.values.filter { !it.isTerminal() && it.orderType == "STP" }.forEach { stop ->
            val mkt = livePrices[stop.symbol] ?: return@forEach
            val stopPx = stop.stopPrice ?: return@forEach
            val triggered = when (stop.action.uppercase()) {
                "SELL" -> mkt <= stopPx
                "BUY" -> mkt >= stopPx
                else -> false
            }
            if (triggered) {
                updateOrder(stop.copy(status = "Cancelled"))
            }
        }
        publishOrders()
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
