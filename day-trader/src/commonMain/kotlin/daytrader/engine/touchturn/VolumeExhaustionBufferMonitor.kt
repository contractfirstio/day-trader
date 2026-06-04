package daytrader.engine.touchturn

import daytrader.domain.TouchTurnRuleConfig
import daytrader.execution.ExecutionManager
import daytrader.marketdata.MarketDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Asynchronous 60-second post-entry observer: cancels the entry order if live volume
 * exceeds the exhaustion threshold before the window ends.
 */
class VolumeExhaustionBufferMonitor(
    private val marketData: MarketDataProvider,
    private val execution: ExecutionManager,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) }
) {
    private val activeJobs = mutableMapOf<String, Job>()

    fun start(
        instanceId: String,
        symbol: String,
        entryOrderId: Int?,
        volumeThreshold: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ) {
        stop(instanceId)
        if (entryOrderId == null || volumeThreshold <= 0.0) return
        VolumeExhaustionLog.bufferActive(instanceId, symbol, entryOrderId, volumeThreshold)
        activeJobs[instanceId] = scope.launch {
            var accumulated = 0.0
            val observer = marketData.observeVolumeTicks(symbol)
                .onEach { tick ->
                    if (tick.volumeDelta > 0.0) accumulated += tick.volumeDelta
                    if (accumulated > volumeThreshold) {
                        VolumeExhaustionLog.orderCancelled(instanceId, symbol, entryOrderId, accumulated)
                        execution.cancelOrder(entryOrderId)
                        stop(instanceId)
                    }
                }
                .launchIn(this)
            val deadline = nowEpochMillis() + rules.volumeBufferObservationMs
            while (isActive && nowEpochMillis() < deadline) {
                delayMillis(POLL_INTERVAL_MS)
            }
            observer.cancel()
            if (activeJobs.containsKey(instanceId)) {
                VolumeExhaustionLog.bufferCompleted(instanceId, symbol, accumulated)
                stop(instanceId)
            }
        }
    }

    fun stop(instanceId: String) {
        activeJobs.remove(instanceId)?.cancel()
    }

    fun stopAll() {
        activeJobs.keys.toList().forEach { stop(it) }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
    }
}
