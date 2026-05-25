package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.TouchTurnTradeSide
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
    fun firstCandle_tradeSide_balancedAcrossCatalog() {
        val config = BrokerEmulatorConfig(firstCandleSecondsUntilClose = 10)
        val now = 1_700_000_000_000L
        var longCount = 0
        var shortCount = 0
        EmulatorSeedCatalog.instruments().forEach { (symbol, instrument) ->
            val bar = EmulatorHistoricalData.firstFifteenMinuteCandle(
                symbol = symbol,
                instrument = instrument,
                config = config,
                nowEpochMillis = now
            ).getOrThrow()
            val adr = EmulatorHistoricalData.fourteenDayAdr(symbol, instrument).getOrThrow()
            val threshold = TouchTurnLogic.liquidityRangeThreshold(adr)
            val setup = TouchTurnLogic.computeBracketSetup(bar, threshold)
            if (!setup.isActionable) return@forEach
            when (setup.side) {
                TouchTurnTradeSide.LONG -> longCount++
                TouchTurnTradeSide.SHORT -> shortCount++
            }
        }
        assertTrue(longCount > 0, "expected at least one long setup in catalog")
        assertTrue(shortCount > 0, "expected at least one short setup in catalog")
        val longShare = longCount.toDouble() / (longCount + shortCount)
        assertTrue(
            longShare in 0.25..0.75,
            "long=$longCount short=$shortCount longShare=$longShare (want ~50/50)"
        )
    }

    @Test
    fun symbolProfile_candleColor_flipsWithSessionDay() {
        val symbol = "AAPL"
        val greenByDay = (1..14).map { day ->
            val ymd = "202605%02d".format(day)
            EmulatorHistoricalData.symbolProfile(symbol, ymd).closeBias > 0
        }.toSet()
        assertEquals(2, greenByDay.size, "expected both green and red candles across session days")
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
