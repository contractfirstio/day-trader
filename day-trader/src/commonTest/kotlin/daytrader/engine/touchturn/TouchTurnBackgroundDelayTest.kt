package daytrader.engine.touchturn

import daytrader.gateway.BrokerKind
import daytrader.replay.ReplayClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TouchTurnBackgroundDelayTest {
    @Test
    fun replayBackgroundDelay_usesWallTimeWithoutAdvancingVirtualClock() = runBlocking {
        val clock = ReplayClock(initialEpochMs = 1_000L)
        delayTouchTurnBackgroundLoop(BrokerKind.REPLAY, 5_000L, clock::delayMillis)
        assertEquals(1_000L, clock.nowEpochMillis())
    }

    @Test
    fun emulatorBackgroundDelay_usesInjectedDelay() = runBlocking {
        var advanced = 0L
        delayTouchTurnBackgroundLoop(BrokerKind.EMULATOR, 3_000L) { advanced += it }
        assertEquals(3_000L, advanced)
    }

    @Test
    fun sessionFiveMinuteBarCollector_replayPollDoesNotRaceVirtualClock() = runBlocking {
        val clock = ReplayClock(initialEpochMs = 1_000L)
        val collectorJob: Job = launch(Dispatchers.Default) {
            repeat(5) {
                delayTouchTurnBackgroundLoop(BrokerKind.REPLAY, 1_000L, clock::delayMillis)
            }
        }
        delay(50)
        assertEquals(1_000L, clock.nowEpochMillis(), "50ms wall time must not advance replay clock by seconds")
        collectorJob.cancel()
    }
}
