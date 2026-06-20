package daytrader.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainThreadWatchdogTest {
    @Test
    fun configFromEnvironment_enabledByDefault() {
        val config = MainThreadWatchdog.configFromEnvironment { null }
        assertNotNull(config)
        assertTrue(config.thresholdMs == 500L)
        assertTrue(config.pollIntervalMs == 100L)
    }

    @Test
    fun configFromEnvironment_disabledWhenEnvFalse() {
        assertNull(
            MainThreadWatchdog.configFromEnvironment { key ->
                if (key == "DAY_TRADER_UI_THREAD_WATCHDOG") "false" else null
            }
        )
    }

    @Test
    fun configFromEnvironment_readsThresholdAndInterval() {
        val env = mapOf(
            "DAY_TRADER_UI_THREAD_WATCHDOG_MS" to "750",
            "DAY_TRADER_UI_THREAD_WATCHDOG_INTERVAL_MS" to "50",
        )
        val config = MainThreadWatchdog.configFromEnvironment { key -> env[key] }
        assertNotNull(config)
        assertTrue(config.thresholdMs == 750L)
        assertTrue(config.pollIntervalMs == 50L)
    }

    @Test
    fun stallDetector_detectsWhenHeartbeatNeverArrives() {
        assertTrue(
            StallDetector.isStalled(
                lastHeartbeatMs = 1_000L,
                checkScheduledAtMs = 1_500L,
                nowMs = 2_100L,
                thresholdMs = 500L,
            )
        )
    }

    @Test
    fun stallDetector_notStalledWhenHeartbeatArrivesInTime() {
        assertFalse(
            StallDetector.isStalled(
                lastHeartbeatMs = 1_550L,
                checkScheduledAtMs = 1_500L,
                nowMs = 1_900L,
                thresholdMs = 500L,
            )
        )
    }

    @Test
    fun stallDetector_notStalledBeforeThresholdElapsed() {
        assertFalse(
            StallDetector.isStalled(
                lastHeartbeatMs = 1_000L,
                checkScheduledAtMs = 1_500L,
                nowMs = 1_900L,
                thresholdMs = 500L,
            )
        )
    }
}
