package daytrader.broker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Merges repeated scheduling into a single [IbRequestPacer] job, respecting [minIntervalMs]
 * between actual runs (e.g. [reqExecutions]).
 */
internal class IbCoalescedPacedRequest(
    private val scope: CoroutineScope,
    private val pacer: IbRequestPacer,
    private val minIntervalMs: Long,
    private val action: () -> Unit
) {
    private val mutex = Mutex()
    private var waitJob: Job? = null
    @Volatile
    private var lastRunAtMs = 0L

    fun schedule() {
        scope.launch {
            mutex.withLock {
                if (waitJob?.isActive == true) return@launch
                waitJob = scope.launch {
                    val now = System.currentTimeMillis()
                    val wait = (minIntervalMs - (now - lastRunAtMs)).coerceAtLeast(0)
                    delay(wait)
                    mutex.withLock { waitJob = null }
                    pacer.enqueue {
                        lastRunAtMs = System.currentTimeMillis()
                        action()
                    }
                }
            }
        }
    }

    fun reset() {
        waitJob?.cancel()
        waitJob = null
        lastRunAtMs = 0L
    }
}
