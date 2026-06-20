package daytrader.data.persistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class DebouncedFileWriter<T : Any>(
    private val scope: CoroutineScope,
    private val debounceMs: Long = SAVE_DEBOUNCE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val persist: (T) -> Unit
) {
    private var saveJob: Job? = null
    private var pending: T? = null
    private var firstScheduledAtMs: Long = 0L

    private sealed interface QueueEvent {
        data class Persist(val value: Any) : QueueEvent
        data class Barrier(val completed: CompletableDeferred<Unit>) : QueueEvent
    }

    private val queue = Channel<QueueEvent>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in queue) {
                when (event) {
                    is QueueEvent.Persist -> runCatching { persist(@Suppress("UNCHECKED_CAST") (event.value as T)) }
                    is QueueEvent.Barrier -> event.completed.complete(Unit)
                }
            }
        }
    }

    fun schedule(value: T) {
        pending = value
        val now = System.currentTimeMillis()
        if (saveJob?.isActive != true) {
            firstScheduledAtMs = now
        } else if (now - firstScheduledAtMs >= maxWaitMs) {
            saveJob?.cancel()
            pending = null
            firstScheduledAtMs = 0L
            enqueuePersist(value)
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            val toWrite = pending ?: return@launch
            pending = null
            firstScheduledAtMs = 0L
            enqueuePersist(toWrite)
        }
    }

    /** Synchronous write for hydration/migration paths only. */
    fun persistNow(value: T) {
        saveJob?.cancel()
        pending = null
        firstScheduledAtMs = 0L
        persist(value)
    }

    /** Enqueues the latest value for background persistence; returns immediately. */
    fun flush(value: T) {
        saveJob?.cancel()
        pending = null
        firstScheduledAtMs = 0L
        enqueuePersist(value)
    }

    /** Enqueues the latest value and blocks until it has been written. */
    fun flushBlocking(value: T) {
        saveJob?.cancel()
        pending = null
        firstScheduledAtMs = 0L
        runBlocking {
            val barrier = CompletableDeferred<Unit>()
            queue.send(QueueEvent.Persist(value))
            queue.send(QueueEvent.Barrier(barrier))
            barrier.await()
        }
    }

    suspend fun awaitIdle() {
        val barrier = CompletableDeferred<Unit>()
        queue.send(QueueEvent.Barrier(barrier))
        barrier.await()
    }

    fun awaitIdleBlocking() {
        runBlocking { awaitIdle() }
    }

    private fun enqueuePersist(value: T) {
        queue.trySend(QueueEvent.Persist(value))
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L
        const val MAX_WAIT_MS = 2_000L
    }
}
