package daytrader.broker.emulator

import kotlin.test.Test
import kotlin.test.assertEquals

class BrokerEmulatorConfigTest {
    @Test
    fun parseFirstCandleSecondsUntilClose() {
        assertEquals(10L, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose(null))
        assertEquals(null, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose("off"))
        assertEquals(120L, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose("120"))
    }
}
