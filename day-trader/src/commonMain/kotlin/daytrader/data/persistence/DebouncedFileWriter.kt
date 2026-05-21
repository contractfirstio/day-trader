package daytrader.data.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DebouncedFileWriter<T>(
    private val scope: CoroutineScope,
    private val debounceMs: Long = SAVE_DEBOUNCE_MS,
    private val persist: (T) -> Unit
) {
    private var saveJob: Job? = null

    fun schedule(value: T) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            persist(value)
        }
    }

    fun persistNow(value: T) {
        saveJob?.cancel()
        persist(value)
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }
}
