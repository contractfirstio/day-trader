package daytrader.data.persistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class DeferredFileHydration {
    private val completed = CompletableDeferred<Unit>()

    fun markComplete() {
        if (!completed.isCompleted) {
            completed.complete(Unit)
        }
    }

    suspend fun awaitComplete() {
        completed.await()
    }
}

internal fun CoroutineScope.launchDeferredFileHydration(
    hydration: DeferredFileHydration,
    load: () -> Unit,
) {
    launch(Dispatchers.Default) {
        try {
            load()
        } finally {
            hydration.markComplete()
        }
    }
}
