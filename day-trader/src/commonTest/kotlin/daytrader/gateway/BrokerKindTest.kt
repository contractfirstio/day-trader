package daytrader.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrokerKindTest {
    @Test
    fun usesLiveIbMarketData_trueForLiveTradingPaths() {
        assertTrue(BrokerKind.INTERACTIVE_BROKERS.usesLiveIbMarketData)
        assertTrue(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.usesLiveIbMarketData)
        assertTrue(BrokerKind.REPLAY.usesLiveIbMarketData)
    }

    @Test
    fun usesLiveIbMarketData_falseForOfflineEmulator() {
        assertFalse(BrokerKind.EMULATOR.usesLiveIbMarketData)
    }

    @Test
    fun usesEmulatorExecution_onlyOfflineSimPaths() {
        assertFalse(BrokerKind.INTERACTIVE_BROKERS.usesEmulatorExecution)
        assertTrue(BrokerKind.EMULATOR.usesEmulatorExecution)
        assertTrue(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.usesEmulatorExecution)
        assertTrue(BrokerKind.REPLAY.usesEmulatorExecution)
    }

    @Test
    fun dataDirectorySegments_areDistinct() {
        val segments = BrokerKind.entries.map { it.dataDirectorySegment }.toSet()
        assertEquals(BrokerKind.entries.size, segments.size)
    }
}
