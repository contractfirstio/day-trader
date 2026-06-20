package daytrader.presentation.ui

import daytrader.presentation.navigation.AppScreen
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object UiCoroutineScopes {
    fun forScreen(screen: AppScreen, tag: String): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineName(tag) +
                CoroutineExceptionHandler { _, throwable ->
                    UiFaultBus.report(screen, tag, throwable)
                }
        )
}
