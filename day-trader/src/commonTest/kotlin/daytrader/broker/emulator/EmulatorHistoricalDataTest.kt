package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
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
            longShare in 0.4..0.6,
            "long=$longCount short=$shortCount longShare=$longShare (want ~50/50)"
        )
    }

    @Test
    fun firstCandle_greenRed_balancedAcrossCatalogOnSessionDay() {
        val catalog = EmulatorSeedCatalog.instruments().keys.sorted()
        val n = catalog.size
        val minGreen = n / 2
        val maxGreen = (n + 1) / 2
        listOf("20260524", "20260525", "20260526").forEach { ymd ->
            val greenCount = catalog.count { symbol ->
                EmulatorHistoricalData.symbolProfile(symbol, ymd).closeBias > 0
            }
            assertTrue(
                greenCount in minGreen..maxGreen,
                "session $ymd: green=$greenCount expected $minGreen..$maxGreen of $n"
            )
        }
    }

    @Test
    fun firstCandle_profileCloseBiasMatchesTouchTurnColor() {
        val ymd = "20260525"
        EmulatorSeedCatalog.instruments().forEach { (symbol, instrument) ->
            val profile = EmulatorHistoricalData.symbolProfile(symbol, ymd)
            val ref = instrument.referencePrice
            val range = ref * profile.intradayRangePct
            val open = ref - range * profile.openBias
            val close = open + range * profile.closeBias
            val bar = daytrader.domain.OhlcBar(open = open, high = close, low = open, close = close)
            val expectedGreen = EmulatorHistoricalData.firstCandleIsGreen(
                SymbolMarkets.normalizeSymbol(symbol),
                ymd
            )
            assertEquals(expectedGreen, profile.closeBias > 0, symbol)
            assertEquals(
                if (expectedGreen) FirstCandleColor.GREEN else FirstCandleColor.RED,
                TouchTurnLogic.firstCandleColor(bar),
                "$symbol open=$open close=$close"
            )
        }
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
