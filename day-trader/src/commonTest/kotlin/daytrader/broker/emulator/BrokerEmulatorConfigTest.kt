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

    @Test
    fun forLiveIbMarketData_usesLiveExchangePricingWithoutEmulatorScenarioTuning() {
        val config = BrokerEmulatorConfig.forLiveIbMarketData()
        assertEquals(EmulatorPricingSource.LIVE_EXCHANGE, config.pricingSource)
        assertEquals(null, config.firstCandleSecondsUntilClose)
        assertEquals(false, config.simulateOrderProgress)
        assertEquals(1.0, config.bracketExitSpreadWidenFactor)
        assertEquals(0.0, config.touchTurnEntryNeverFillProbability)
        assertEquals(false, config.alternateFirstCandleColor)
    }
}
