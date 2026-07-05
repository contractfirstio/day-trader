package daytrader.test

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Assigns a unique on-disk root per test JVM when [DAY_TRADER_DATA_DIR] is unset.
 * Gradle [maxParallelForks] shares one task-level environment across workers, so isolation
 * must be per-process (PID), not per Gradle Test task.
 */
internal object TestJvmIsolation {
    private val configured = AtomicBoolean(false)

    fun ensureJvmDataDirectory() {
        if (!configured.compareAndSet(false, true)) return
        if (!System.getenv("DAY_TRADER_DATA_DIR").isNullOrBlank()) return
        if (!System.getProperty("daytrader.data.dir").isNullOrBlank()) return
        val dir = Files.createTempDirectory("day-trader-test-${ProcessHandle.current().pid()}-")
        System.setProperty("daytrader.data.dir", dir.toString())
    }
}
