package daytrader.marketdata

import daytrader.gateway.LiveQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.ConcurrentHashMap

/**
 * Drains the UI subscriber queue from [MarketQuoteBus] and invokes [onSnapshot] with the
 * latest quote per symbol (throttled). Keeps chart marks responsive without blocking IB.
 */
class MarketQuoteBusUiRelay(
    private val bus: MarketQuoteBus,
    private val scope: CoroutineScope,
    private val onSnapshot: (Map<String, LiveQuote>) -> Unit,
    private val throttleMs: Long = DEFAULT_THROTTLE_MS
) {
    private val latestBySymbol = ConcurrentHashMap<String, LiveQuote>()
    private var collectorJob: Job? = null
    private var throttleJob: Job? = null

    fun start() {
        if (collectorJob != null) return
        val channel = bus.subscribe(
            subscriberId = MarketQuoteBus.UI_SUBSCRIBER_ID,
            capacity = MarketQuoteBus.UI_SUBSCRIBER_BUFFER,
            onOverflow = BufferOverflow.DROP_OLDEST
        )
        collectorJob = scope.launch {
            for (update in channel) {
                latestBySymbol[update.symbol] = update.quote
            }
        }
        throttleJob = scope.launch {
            while (isActive) {
                delay(throttleMs)
                if (latestBySymbol.isNotEmpty()) {
                    onSnapshot(latestBySymbol.toMap())
                }
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        throttleJob?.cancel()
        throttleJob = null
        bus.unsubscribe(MarketQuoteBus.UI_SUBSCRIBER_ID)
        latestBySymbol.clear()
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 50L
    }
}
