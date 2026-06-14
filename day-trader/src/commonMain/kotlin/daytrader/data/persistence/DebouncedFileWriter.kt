package daytrader.data.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DebouncedFileWriter<T>(
    private val scope: CoroutineScope,
    private val debounceMs: Long = SAVE_DEBOUNCE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val persist: (T) -> Unit
) {
    private var saveJob: Job? = null
    private var pending: T? = null
    private var firstScheduledAtMs: Long = 0L

    fun schedule(value: T) {
        pending = value
        val now = System.currentTimeMillis()
        if (saveJob?.isActive != true) {
            firstScheduledAtMs = now
        } else if (now - firstScheduledAtMs >= maxWaitMs) {
            saveJob?.cancel()
            pending = null
            firstScheduledAtMs = 0L
            persist(value)
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            val toWrite = pending ?: return@launch
            pending = null
            firstScheduledAtMs = 0L
            persist(toWrite)
        }
    }

    fun persistNow(value: T) {
        saveJob?.cancel()
        pending = null
        firstScheduledAtMs = 0L
        persist(value)
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L
        const val MAX_WAIT_MS = 2_000L
    }
}
