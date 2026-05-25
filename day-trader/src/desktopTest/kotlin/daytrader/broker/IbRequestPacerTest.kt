package daytrader.broker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
}
