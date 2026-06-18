package daytrader.engine

import kotlinx.coroutines.flow.Flow

interface TouchTurnEnginePort {
    fun dispatch(command: TouchTurnCommand)
    val events: Flow<TouchTurnEvent>
    fun start()
    /** Stops command processing, polling loops, and per-session jobs (broker mode switch / app exit). */
    fun shutdown() {}
    fun updateGlobalAutoStartEnabled(enabled: Boolean) {}
    /** Clears per-instance tracking and broker snapshots when no session is running (replay boundaries). */
    fun resetSessionMemory(instanceId: String? = null) {}
}
