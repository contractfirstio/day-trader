package daytrader.platform

import daytrader.diagnostics.TimestampedConsoleLog
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Detects AWT/Compose UI thread stalls and logs stack traces for diagnosis.
 *
 * Enabled by default; set `DAY_TRADER_UI_THREAD_WATCHDOG=false` to disable.
 * Optional overrides:
 * - `DAY_TRADER_UI_THREAD_WATCHDOG_MS` — stall threshold (default 500)
 * - `DAY_TRADER_UI_THREAD_WATCHDOG_INTERVAL_MS` — poll interval (default 100)
 */
object MainThreadWatchdog {
    private const val TAG = "UI_THREAD_WATCHDOG"
    private const val DEFAULT_THRESHOLD_MS = 500L
    private const val DEFAULT_POLL_INTERVAL_MS = 100L

    fun installIfEnabled() {
        val config = configFromEnvironment() ?: return
        install(config)
    }

    internal fun configFromEnvironment(
        getenv: (String) -> String? = System::getenv,
    ): Config? {
        val disabled = getenv("DAY_TRADER_UI_THREAD_WATCHDOG")
            ?.equals("false", ignoreCase = true) == true
        if (disabled) return null
        val thresholdMs = getenv("DAY_TRADER_UI_THREAD_WATCHDOG_MS")
            ?.toLongOrNull()
            ?.coerceAtLeast(50L)
            ?: DEFAULT_THRESHOLD_MS
        val pollIntervalMs = getenv("DAY_TRADER_UI_THREAD_WATCHDOG_INTERVAL_MS")
            ?.toLongOrNull()
            ?.coerceAtLeast(25L)
            ?: DEFAULT_POLL_INTERVAL_MS
        return Config(thresholdMs = thresholdMs, pollIntervalMs = pollIntervalMs)
    }

    internal fun install(config: Config) {
        val lastHeartbeatMs = AtomicLong(System.currentTimeMillis())
        var stallActive = false

        thread(name = "UiThreadWatchdog", isDaemon = true) {
            TimestampedConsoleLog.line(
                TAG,
                "enabled thresholdMs=${config.thresholdMs} pollIntervalMs=${config.pollIntervalMs}",
            )
            while (true) {
                Thread.sleep(config.pollIntervalMs)
                val checkScheduledAtMs = System.currentTimeMillis()
                SwingUtilities.invokeLater {
                    lastHeartbeatMs.set(System.currentTimeMillis())
                }

                val deadlineMs = checkScheduledAtMs + config.thresholdMs
                while (System.currentTimeMillis() < deadlineMs) {
                    if (lastHeartbeatMs.get() >= checkScheduledAtMs) break
                    Thread.sleep(10L)
                }

                val stalled = StallDetector.isStalled(
                    lastHeartbeatMs = lastHeartbeatMs.get(),
                    checkScheduledAtMs = checkScheduledAtMs,
                    nowMs = System.currentTimeMillis(),
                    thresholdMs = config.thresholdMs,
                )
                when {
                    stalled && !stallActive -> {
                        stallActive = true
                        val lagMs = System.currentTimeMillis() - checkScheduledAtMs
                        TimestampedConsoleLog.line(
                            TAG,
                            "UI thread stalled for ~${lagMs}ms (threshold=${config.thresholdMs}ms)",
                        )
                        logUiThreadStacks()
                    }
                    !stalled && stallActive -> {
                        stallActive = false
                        TimestampedConsoleLog.line(TAG, "UI thread responsive again")
                    }
                }
            }
        }
    }

    private fun logUiThreadStacks() {
        val traces = Thread.getAllStackTraces()
        val uiThreads = traces.filterKeys(::isLikelyUiThread)
        if (uiThreads.isEmpty()) {
            TimestampedConsoleLog.line(TAG, "No UI thread stack trace available")
            return
        }
        uiThreads.forEach { (uiThread, stack) ->
            TimestampedConsoleLog.multiline(
                TAG,
                buildString {
                    append("thread=${uiThread.name} state=${uiThread.state}\n")
                    stack.take(40).forEach { frame ->
                        append("  at $frame\n")
                    }
                }.trimEnd(),
            )
        }
    }

    private fun isLikelyUiThread(thread: Thread): Boolean {
        val name = thread.name
        return name.contains("EventQueue", ignoreCase = true) ||
            name.equals("main", ignoreCase = true) ||
            name.contains("AWT", ignoreCase = true) && name.contains("Thread", ignoreCase = true)
    }

    internal data class Config(
        val thresholdMs: Long,
        val pollIntervalMs: Long,
    )
}

internal object StallDetector {
    fun isStalled(
        lastHeartbeatMs: Long,
        checkScheduledAtMs: Long,
        nowMs: Long,
        thresholdMs: Long,
    ): Boolean =
        nowMs - checkScheduledAtMs >= thresholdMs && lastHeartbeatMs < checkScheduledAtMs
}
