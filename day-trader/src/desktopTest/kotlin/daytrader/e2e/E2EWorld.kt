package daytrader.e2e

import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.E2ESessionDriver
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.HybridModeTestHarness
import daytrader.e2e.support.IbModeTestHarness
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
class E2EWorld {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository: InMemoryStrategyDeploymentRepository = InMemoryStrategyDeploymentRepository()

    var brokerMode: String = "emulator"
    var symbol: String = E2ETestFixtures.SYMBOL

    var emulatorHarness: EmulatorModeTestHarness? = null
    var hybridHarness: HybridModeTestHarness? = null
    var ibHarness: IbModeTestHarness? = null

    var engine: TouchTurnEnginePort? = null
    var driver: E2ESessionDriver? = null
    var lastInstrumentResolution: Boolean = false

    fun reset() {
        emulatorHarness?.shutdown()
        hybridHarness?.shutdown()
        ibHarness?.shutdown()

        emulatorHarness = null
        hybridHarness = null
        ibHarness = null
        engine = null
        driver = null
        repository.deployments.value.toList().forEach { repository.remove(it.id) }
    }

    fun configureEmulatorHarness(factory: (CoroutineScope) -> EmulatorModeTestHarness) {
        emulatorHarness = factory(scope)
    }

    fun activeEmulatorHarness(): EmulatorModeTestHarness =
        emulatorHarness ?: EmulatorModeTestHarness(scope).also { emulatorHarness = it }

    fun activeHybridHarness(): HybridModeTestHarness =
        hybridHarness ?: HybridModeTestHarness(scope).also { hybridHarness = it }

    fun activeIbHarness(): IbModeTestHarness =
        ibHarness ?: IbModeTestHarness().also { ibHarness = it }
}
