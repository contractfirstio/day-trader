package daytrader.e2e

import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.E2EProcessCleanup
import daytrader.e2e.support.E2ESessionDriver
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.HybridModeTestHarness
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEmulatorHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.e2e.support.shutdownIbHarness
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class E2EWorld {
    private var _scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val scope: CoroutineScope get() = _scope
    val repository: InMemoryStrategyDeploymentRepository = InMemoryStrategyDeploymentRepository()

    private val testClock = AtomicLong(E2ETestFixtures.BAR_CLOSE_EPOCH_MS)

    /** Adjustable wall clock for engine five-minute TTL / expiry E2E scenarios. */
    fun testNowEpochMillis(): () -> Long = { testClock.get() }

    fun resetTestClock() {
        testClock.set(E2ETestFixtures.BAR_CLOSE_EPOCH_MS)
    }

    fun advanceTestClockTo(epochMs: Long) {
        testClock.set(epochMs)
    }

    fun advanceTestClockBy(deltaMs: Long) {
        testClock.addAndGet(deltaMs)
    }

    var brokerMode: String = "emulator"
    var symbol: String = E2ETestFixtures.SYMBOL

    var emulatorHarness: EmulatorModeTestHarness? = null
    var hybridHarness: HybridModeTestHarness? = null
    var ibHarness: IbModeTestHarness? = null

    var engine: TouchTurnEnginePort? = null
    var driver: E2ESessionDriver? = null
    var lastInstrumentResolution: Boolean = false

    fun reset() {
        shutdownResources()
        E2EProcessCleanup.resetAll()
        repository.deployments.value.toList().forEach { repository.remove(it.id) }
        _scope.cancel()
        _scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        resetTestClock()
        lastInstrumentResolution = false
    }

    private fun shutdownResources() {
        runBlocking {
            val activeEngine = engine
            if (activeEngine != null) {
                runCatching { activeEngine.drainUntilIdle(512) }
                activeEngine.shutdownEngine()
            }
        }
        engine = null
        driver = null
        ibHarness?.gateway?.resetTestFixtures()
        ibHarness.shutdownIbHarness()
        emulatorHarness.shutdownEmulatorHarness()
        hybridHarness?.shutdown()
        emulatorHarness = null
        hybridHarness = null
        ibHarness = null
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
