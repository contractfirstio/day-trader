package daytrader.broker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Serializes outbound IB API calls with:
 * 1. A sliding-window cap ([maxMessagesPerSecond], default 25 — under IB's ~50/sec limit)
 * 2. A minimum gap between consecutive calls ([minIntervalMs], default 50ms)
 *
 * Every outbound `client.*` request must go through [enqueue] (one IB message per action).
 */
internal class IbRequestPacer(
    private val scope: CoroutineScope,
    maxMessagesPerSecond: Int = IbRateLimits.maxMessagesPerSecond(),
    minIntervalMs: Long = IbRateLimits.minIntervalMs()
) {
  /** Drained before [queue] so bracket legs are not stuck behind historical data. */
    private val priorityQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private val baselineMaxPerSecond =
        maxMessagesPerSecond.coerceIn(1, IbRateLimits.IB_HARD_LIMIT_PER_SECOND - 1)
    private val baselineMinInterval = minIntervalMs.coerceAtLeast(1L)
    @Volatile
    private var effectiveMaxPerSecond = baselineMaxPerSecond
    @Volatile
    private var effectiveMinInterval = baselineMinInterval
    private val recentCallTimes = ArrayDeque<Long>()
    private val windowLock = Any()

    @Volatile
    private var drainJob: Job? = null
    private var backoffRestoreJob: Job? = null

    fun enqueue(action: () -> Unit) {
        queue.offer(action)
        startDrainIfNeeded()
    }

    /** Same rate limits as [enqueue], but runs ahead of the standard backlog. */
    fun enqueuePriority(action: () -> Unit) {
        priorityQueue.offer(action)
        startDrainIfNeeded()
    }

    fun clear() {
        priorityQueue.clear()
        queue.clear()
        drainJob?.cancel()
        drainJob = null
        backoffRestoreJob?.cancel()
        backoffRestoreJob = null
        restoreBaselineRate()
        synchronized(windowLock) {
            recentCallTimes.clear()
        }
    }

    /** Halves throughput temporarily after IB API error 100 (50 msg/sec exceeded). */
    fun applyRateLimitBackoff() {
        effectiveMaxPerSecond = (effectiveMaxPerSecond / 2).coerceAtLeast(5)
        effectiveMinInterval = (effectiveMinInterval * 2).coerceAtMost(250L)
        IbGatewayLog.rateLimitBackoff(effectiveMaxPerSecond, effectiveMinInterval)
        backoffRestoreJob?.cancel()
        backoffRestoreJob = scope.launch {
            delay(IbRateLimits.RATE_LIMIT_BACKOFF_MS)
            restoreBaselineRate()
            IbGatewayLog.rateLimitBackoffRestored(baselineMaxPerSecond, baselineMinInterval)
        }
    }

    private fun restoreBaselineRate() {
        effectiveMaxPerSecond = baselineMaxPerSecond
        effectiveMinInterval = baselineMinInterval
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
            val action = priorityQueue.poll() ?: queue.poll() ?: break
            awaitRateLimitSlot()
            try {
                action()
            } catch (e: Exception) {
                IbGatewayLog.pacerFailure(e)
            }
        }
        synchronized(this) {
            if (priorityQueue.isNotEmpty() || queue.isNotEmpty()) {
                drainJob = scope.launch { drainQueue() }
            } else {
                drainJob = null
            }
        }
    }

    private suspend fun awaitRateLimitSlot() {
        while (true) {
            val maxPerSecond = effectiveMaxPerSecond
            val minInterval = effectiveMinInterval
            val waitMs = synchronized(windowLock) {
                val now = System.currentTimeMillis()
                pruneCallsOlderThan(now - WINDOW_MS)
                var wait = 0L
                if (recentCallTimes.isNotEmpty()) {
                    val sinceLast = now - recentCallTimes.last()
                    if (sinceLast < minInterval) {
                        wait = maxOf(wait, minInterval - sinceLast)
                    }
                }
                if (recentCallTimes.size >= maxPerSecond) {
                    val oldest = recentCallTimes.first()
                    val untilWindow = WINDOW_MS - (now - oldest)
                    if (untilWindow > 0) {
                        wait = maxOf(wait, untilWindow)
                    }
                }
                if (wait > 0L) {
                    return@synchronized wait
                }
                recentCallTimes.addLast(now)
                0L
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    private fun pruneCallsOlderThan(cutoffMs: Long) {
        while (recentCallTimes.isNotEmpty() && recentCallTimes.first() < cutoffMs) {
            recentCallTimes.removeFirst()
        }
    }

    companion object {
        private const val WINDOW_MS = 1_000L
        const val RECONNECT_DELAY_MS = 1_500L
    }
}
