package daytrader.presentation.ui

import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.presentation.navigation.AppScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiFault(
    val screen: AppScreen,
    val source: String,
    val message: String,
    val occurredAtEpochMs: Long = System.currentTimeMillis(),
)

object UiFaultBus {
    private val _faults = MutableStateFlow<Map<AppScreen, UiFault>>(emptyMap())
    val faults: StateFlow<Map<AppScreen, UiFault>> = _faults.asStateFlow()

    fun report(screen: AppScreen, source: String, throwable: Throwable) {
        logFault(source, throwable)
        val message = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.simpleName.orEmpty().ifBlank { "Unknown error" }
        _faults.update { current ->
            current + (screen to UiFault(screen = screen, source = source, message = message))
        }
    }

    fun clear(screen: AppScreen) {
        _faults.update { it - screen }
    }

    fun clearAll() {
        _faults.value = emptyMap()
    }

    private fun logFault(source: String, throwable: Throwable) {
        TimestampedConsoleLog.line(
            tag = "UI_FAULT",
            message = "$source ${throwable::class.simpleName}: ${throwable.message}",
        )
        throwable.printStackTrace()
    }
}

fun safeUiEmit(screen: AppScreen, source: String, block: () -> Unit) {
    runCatching(block).onFailure { UiFaultBus.report(screen, source, it) }
}
