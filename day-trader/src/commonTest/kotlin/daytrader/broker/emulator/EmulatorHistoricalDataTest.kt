package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.TouchTurnLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmulatorHistoricalDataTest {

    @Test
    fun acceleratedFirstCandle_isFormingThenClosedAfterConfiguredDelay() {
        val now = 1_700_000_000_000L
        val instrument = EmulatorSeedCatalog.instruments()["SPY"]!!
        val config = BrokerEmulatorConfig(firstCandleSecondsUntilClose = 10)
        val bar = EmulatorHistoricalData.firstFifteenMinuteCandle(
            symbol = "SPY",
            instrument = instrument,
            config = config,
            nowEpochMillis = now
        ).getOrThrow()
        val zone = SymbolMarkets.zoneId("SPY")

        assertEquals(
            FirstCandleCloseStatus.FORMING,
            TouchTurnLogic.firstCandleCloseStatus(bar, zone, now)
        )
        assertEquals(
            FirstCandleCloseStatus.CLOSED,
            TouchTurnLogic.firstCandleCloseStatus(bar, zone, now + 10_001)
        )
    }

    @Test
    fun legacyFirstCandle_usesToday0930Open() {
        val now = 1_700_000_000_000L
        val instrument = EmulatorSeedCatalog.instruments()["SPY"]!!
        val config = BrokerEmulatorConfig(firstCandleSecondsUntilClose = null)
        val bar = EmulatorHistoricalData.firstFifteenMinuteCandle(
            symbol = "SPY",
            instrument = instrument,
            config = config,
            nowEpochMillis = now
        ).getOrThrow()

        assertTrue(bar.time?.contains("09:30") == true)
    }

    @Test
    fun parseFirstCandleSecondsUntilClose_defaultsOffAndCustom() {
        assertEquals(10L, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose(null))
        assertEquals(10L, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose(""))
        assertEquals(null, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose("off"))
        assertEquals(30L, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose("30"))
        assertEquals(null, BrokerEmulatorConfig.parseFirstCandleSecondsUntilClose("0"))
    }
}
