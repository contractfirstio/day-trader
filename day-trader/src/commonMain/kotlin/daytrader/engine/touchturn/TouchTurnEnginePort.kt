package daytrader.engine

import kotlinx.coroutines.flow.Flow

interface TouchTurnEnginePort {
    fun dispatch(command: TouchTurnCommand)
    val events: Flow<TouchTurnEvent>
    fun start()
    fun updateGlobalAutoStartEnabled(enabled: Boolean) {}
}
