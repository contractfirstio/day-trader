package daytrader.replay

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One headless replay/backtest at a time across Gradle test workers and broker-mode tasks.
 * Matches [ReplaySessionController.beginBatchReplayIsolation] intent when tests run in parallel.
 */
internal object ReplayHeadlessProcessLock {
    private val lockPath: Path =
        Path.of(System.getProperty("java.io.tmpdir"), "day-trader-headless-replay.lock")

    suspend fun <T> withExclusiveLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }
}
