package daytrader.replay

import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.TouchTurnEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Starts interactive replay playback when a Touch Turn session boots; stops on session end. */
class ReplaySessionPlaybackBridge(
    private val orchestrator: ReplayPlaybackOrchestrator,
    private val scope: CoroutineScope
) {
    fun attach(engine: TouchTurnEnginePort) {
        engine.events
            .onEach { event ->
                when (event) {
                    is TouchTurnEvent.SessionStarted -> orchestrator.onSessionStarted(event.instanceId)
                    is TouchTurnEvent.SessionStopped -> orchestrator.stop(event.instanceId)
                    else -> Unit
                }
            }
            .launchIn(scope)
    }
}
