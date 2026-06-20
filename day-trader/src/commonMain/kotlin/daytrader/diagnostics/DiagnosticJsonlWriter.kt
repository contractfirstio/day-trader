package daytrader.diagnostics

import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * Background JSONL append queue for diagnostic logs (session traces, price logs, etc.).
 *
 * Callers enqueue lines and return immediately; a single IO worker performs disk appends so
 * engine, broker, and UI threads are not blocked on document persistence or
 * [AppFileSystem]'s log-append lock contending with atomic JSON writes.
 *
 * Use [awaitIdle] / [awaitIdleBlocking] when a caller must observe prior enqueued lines on disk
 * (for example before migrating a pending session log into a session directory).
 */
internal object DiagnosticJsonlWriter {
    private const val QUEUE_CAPACITY = 65_536

    private sealed interface Event {
        data class Append(val relativePath: String, val line: String) : Event
        data class Barrier(val completed: CompletableDeferred<Unit>) : Event
    }

    private val queue = Channel<Event>(capacity = QUEUE_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val droppedLines = AtomicLong(0)

    init {
        scope.launch {
            for (event in queue) {
                when (event) {
                    is Event.Append -> appendToDisk(event.relativePath, event.line)
                    is Event.Barrier -> event.completed.complete(Unit)
                }
            }
        }
    }

    fun appendLine(relativePath: String, line: String) {
        val result = queue.trySend(Event.Append(relativePath, line))
        if (result.isFailure) {
            val dropped = droppedLines.incrementAndGet()
            if (System.getenv("DAY_TRADER_DIAGNOSTIC_LOG_DROP_WARN")?.equals("true", ignoreCase = true) == true) {
                TimestampedConsoleLog.line(
                    "DiagnosticJsonlWriter",
                    "Queue full; dropped $dropped line(s) so far (path=$relativePath)"
                )
            }
        }
    }

    suspend fun awaitIdle() {
        val barrier = CompletableDeferred<Unit>()
        queue.send(Event.Barrier(barrier))
        barrier.await()
    }

    fun awaitIdleBlocking() {
        runBlocking { awaitIdle() }
    }

    private fun appendToDisk(relativePath: String, line: String) {
        runCatching { AppFileSystem.appendLine(relativePath, "$line\n") }
    }
}
