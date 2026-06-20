package daytrader.presentation.ui

import daytrader.presentation.navigation.AppScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object UiRecoveryBus {
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun resetAllUiState() {
        UiFaultBus.clearAll()
        _generation.update { it + 1 }
    }
}

inline fun <T> safeUiMap(screen: AppScreen, source: String, block: () -> T): T? =
    runCatching(block).onFailure { UiFaultBus.report(screen, source, it) }.getOrNull()
