package daytrader.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ReplayClockTest {
    @Test
    fun delayMillis_advancesVirtualTime() = runBlocking {
        val clock = ReplayClock(initialEpochMs = 1_000L)
        clock.delayMillis(3_000L)
        assertEquals(4_000L, clock.nowEpochMillis())
    }

    @Test
    fun delayMillis_withoutWallClockDelays_advancesVirtualTimeOnly() = runBlocking {
        val clock = ReplayClock(initialEpochMs = 1_000L).apply { useWallClockDelays = false }
        clock.delayMillis(3_000L)
        assertEquals(4_000L, clock.nowEpochMillis())
    }
}
