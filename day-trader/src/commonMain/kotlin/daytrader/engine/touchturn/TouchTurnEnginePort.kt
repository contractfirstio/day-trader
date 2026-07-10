package daytrader.engine

import kotlinx.coroutines.flow.Flow

interface TouchTurnEnginePort {
    fun dispatch(command: TouchTurnCommand)
    /**
     * Headless backtest: run [command] on the calling coroutine instead of enqueueing it for the
     * async command loop. Interactive UI sessions keep async [dispatch].
     */
    fun setBacktestSyncCommands(enabled: Boolean) {}
    /** Applies [command] and waits for spawned engine work to settle. */
    suspend fun dispatchAndAwait(command: TouchTurnCommand, idleSpins: Int = 512) {
        dispatch(command)
        drainUntilIdle(idleSpins)
    }
    val events: Flow<TouchTurnEvent>
    fun start()
    /** Stops command processing, polling loops, and per-session jobs (broker mode switch / app exit). */
    fun shutdown() {}
    fun updateGlobalAutoStartEnabled(enabled: Boolean) {}
    fun updateAutoLiquidityFlushEnabled(enabled: Boolean) {}
    /** Clears per-instance tracking and broker snapshots when no session is running (replay boundaries). */
    fun resetSessionMemory(instanceId: String? = null) {}
    /**
     * Yields until in-flight engine jobs finish (headless replay backtest). Default is a no-op for
     * test doubles and engines that do not spawn async work on the caller's critical path.
     */
    suspend fun drainUntilIdle(maxSpins: Int = 512) {}
}
