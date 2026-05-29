package daytrader.ui

/**
 * Registered from [App] so the desktop [Window] can confirm quit when sessions are running.
 */
data class ApplicationQuitCoordinator(
    val hasRunningSessions: () -> Boolean,
    val runningSymbols: () -> List<String>,
    val stopRunningSessions: () -> Unit
)
