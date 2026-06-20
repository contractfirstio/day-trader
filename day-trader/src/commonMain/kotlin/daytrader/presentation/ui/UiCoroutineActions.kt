package daytrader.presentation.ui

import daytrader.presentation.navigation.AppScreen
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun CoroutineScope.launchUiAction(
    screen: AppScreen,
    source: String,
    onFailure: (suspend (Throwable) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        UiFaultBus.report(screen, source, error)
        onFailure?.invoke(error)
    }
}
