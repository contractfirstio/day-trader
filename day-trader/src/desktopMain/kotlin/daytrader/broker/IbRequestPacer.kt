package daytrader.broker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Serializes outbound IB API calls so bursts stay under Gateway pacing limits
 * (~50 messages/sec). Default spacing is ~6–7 requests/sec.
 */
internal class IbRequestPacer(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
) {
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile
    private var drainJob: Job? = null

    fun enqueue(action: () -> Unit) {
        queue.offer(action)
        startDrainIfNeeded()
    }

    fun clear() {
        queue.clear()
        drainJob?.cancel()
        drainJob = null
    }

    private fun startDrainIfNeeded() {
        synchronized(this) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch {
                drainQueue()
            }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val action = queue.poll() ?: break
            try {
                action()
            } catch (e: Exception) {
                IbGatewayLog.pacerFailure(e)
            }
            delay(minIntervalMs)
        }
        synchronized(this) {
            if (queue.isNotEmpty()) {
                drainJob = scope.launch { drainQueue() }
            } else {
                drainJob = null
            }
        }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 150L
        const val RECONNECT_DELAY_MS = 1_500L
    }
}
