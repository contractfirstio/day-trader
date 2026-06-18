package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Drives [daytrader.engine.TouchTurnEngine] through a captured [SessionBundle] using virtual time (Tier A replay).
 */
class ReplaySessionRunner(
    private val bundle: SessionBundle,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope
) {
    suspend fun run(): ReplayComparison {
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(bundle, clock, scope)
        runtime.start()
        val engine = runtime.createEngine(repository)
        runtime.attachSessionEngine(engine)
        runtime.playbackOrchestrator.attach(engine, repository)
        engine.start()
        return try {
            val controller = ReplaySessionController(runtime, repository, engine, scope)
            controller.runReplay()
        } finally {
            engine.shutdown()
            runtime.shutdown()
        }
    }
}
