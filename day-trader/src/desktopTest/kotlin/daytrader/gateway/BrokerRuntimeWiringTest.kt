package daytrader.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Verifies [BrokerRuntime] gateway composition for emulator mode (no live IB required).
 * Hybrid and IB runtime wiring is covered by Cucumber E2E tests with mocked IB gateways.
 */
class BrokerRuntimeWiringTest {

    @Test
    fun createEmulator_runtimeUsesEmulatorGateway() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = BrokerRuntime.create(BrokerKind.EMULATOR, scope)
        runtime.start()
        delay(500)
        assertEquals(BrokerKind.EMULATOR, runtime.kind)
        assertEquals(BrokerId.EMULATOR, runtime.gateway.brokerId)
        assertEquals(null, runtime.marketDataGateway)
        assertNotNull(runtime.ensureLiveMarketData)
        assertNotNull(runtime.releaseLiveMarketData)
        runtime.shutdown()
    }

    @Test
    fun createEmulator_quoteBusAvailableForSyntheticMarks() {
        val runtime = BrokerRuntime.create(BrokerKind.EMULATOR)
        assertNotNull(runtime.quoteBus)
    }

    @Test
    fun createIb_runtimeWiresStreamingMarketDataHooks() {
        val runtime = BrokerRuntime.create(BrokerKind.INTERACTIVE_BROKERS)
        assertEquals(BrokerKind.INTERACTIVE_BROKERS, runtime.kind)
        assertEquals(BrokerId.INTERACTIVE_BROKERS, runtime.gateway.brokerId)
        assertEquals(null, runtime.marketDataGateway)
        assertNotNull(runtime.ensureLiveMarketData)
        assertNotNull(runtime.releaseLiveMarketData)
        assertNotNull(runtime.getStreamingMarketDataType)
        assertNotNull(runtime.setStreamingMarketDataType)
    }
}
