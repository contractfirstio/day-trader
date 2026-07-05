package daytrader.broker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class IbRequestPacerTest {
    @Test
    fun enqueue_respectsMaxMessagesPerSecond() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pacer = IbRequestPacer(scope, maxMessagesPerSecond = 5, minIntervalMs = 1L)
        val count = AtomicInteger(0)
        repeat(10) {
            pacer.enqueue { count.incrementAndGet() }
        }
        delay(2_500)
        assertTrue(count.get() <= 12, "expected ~5/sec cap, got ${count.get()}")
        assertTrue(count.get() >= 8, "expected most jobs to run, got ${count.get()}")
    }

    @Test
    fun applyRateLimitBackoff_still_drains_queue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pacer = IbRequestPacer(scope, maxMessagesPerSecond = 20, minIntervalMs = 1L)
        val count = AtomicInteger(0)
        pacer.applyRateLimitBackoff()
        repeat(3) {
            pacer.enqueue { count.incrementAndGet() }
        }
        delay(1_500)
        assertTrue(count.get() >= 3, "backoff should not block queue drain, got ${count.get()}")
    }

    @Test
    fun enqueuePriority_runsBeforeStandardBacklog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pacer = IbRequestPacer(scope, maxMessagesPerSecond = 20, minIntervalMs = 1L)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val releaseSlowBacklog = CountDownLatch(1)
        pacer.enqueue {
            releaseSlowBacklog.await()
            order += "slow-1"
        }
        pacer.enqueue { order += "slow-2" }
        pacer.enqueuePriority { order += "priority" }
        releaseSlowBacklog.countDown()
        withTimeout(3_000) {
            while (!order.contains("priority") || !order.contains("slow-2")) {
                delay(10)
            }
        }
        assertTrue(order.indexOf("priority") < order.indexOf("slow-2"), "priority order: $order")
    }
}
