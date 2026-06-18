package daytrader.engine

import daytrader.diagnostics.TimestampedConsoleLog
import kotlinx.coroutines.flow.Flow

class LoggingTouchTurnEngine(
    private val delegate: TouchTurnEnginePort,
    private val log: (String) -> Unit = { TimestampedConsoleLog.line("TouchTurnEngine", it) }
) : TouchTurnEnginePort {
    override val events: Flow<TouchTurnEvent> = delegate.events

    override fun dispatch(command: TouchTurnCommand) {
        log("dispatch $command")
        delegate.dispatch(command)
    }

    override fun start() = delegate.start()

    override fun shutdown() = delegate.shutdown()

    override fun updateGlobalAutoStartEnabled(enabled: Boolean) =
        delegate.updateGlobalAutoStartEnabled(enabled)

    override fun resetSessionMemory(instanceId: String?) =
        delegate.resetSessionMemory(instanceId)
}
