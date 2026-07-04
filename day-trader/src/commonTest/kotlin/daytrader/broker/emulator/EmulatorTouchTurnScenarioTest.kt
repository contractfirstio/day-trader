package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnTradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmulatorTouchTurnScenarioTest {
    private val instrument = EmulatorSeedCatalog.instruments()["700"]!!

    @Test
    fun parse_acceptsAliases() {
        assertEquals(EmulatorTouchTurnScenario.GREEN_SHORT_TP, EmulatorTouchTurnScenario.parse("green_short_tp"))
        assertEquals(EmulatorTouchTurnScenario.RED_LONG_SL, EmulatorTouchTurnScenario.parse("red-sl"))
        assertEquals(EmulatorTouchTurnScenario.GREEN_SHORT, EmulatorTouchTurnScenario.parse("short"))
        assertEquals(null, EmulatorTouchTurnScenario.parse("off"))
        assertEquals(null, EmulatorTouchTurnScenario.parse(null))
    }

    @Test
    fun withTouchTurnScenarioDefaults_fastPathAndForcedExit() {
        val config = BrokerEmulatorConfig(
            touchTurnScenario = EmulatorTouchTurnScenario.RED_LONG_TP
        ).withTouchTurnScenarioDefaults()
        assertEquals(EmulatorFirstCandleColorMode.RED, config.firstCandleColorMode)
        assertEquals(true, config.touchTurnEntryFillImmediately)
        assertEquals(0.0, config.touchTurnEntryNeverFillProbability)
        assertEquals(0L, config.fiveMinuteBarSecondsUntilClose)
        assertEquals(1.0, config.bracketExitTakeProfitProbability)
        assertEquals(500L, config.marketTickIntervalMs)
        assertEquals(25, config.bracketExitMinWalkTicks)
        assertEquals(0.012, config.bracketWalkStepPctOfRange)
    }

    @Test
    fun staticOpeningBar_greenAndRed_passLiquidityAndCloseZone() {
        val now = 1_780_000_000_000L
        listOf(
            EmulatorTouchTurnScenario.GREEN_SHORT_TP,
            EmulatorTouchTurnScenario.RED_LONG_TP
        ).forEach { scenario ->
            val bar = EmulatorStaticTouchTurnBars.openingFifteenMinuteBar(
                instrument = instrument,
                scenario = scenario,
                marketZoneId = instrument.marketZoneId,
                nowEpochMillis = now
            )
            val color = TouchTurnLogic.firstCandleColor(bar)
            if (scenario.isGreenOpeningBar) {
                assertEquals(FirstCandleColor.GREEN, color)
            } else {
                assertEquals(FirstCandleColor.RED, color)
            }
            assertEquals(
                daytrader.domain.FirstCandleCloseStatus.CLOSED,
                TouchTurnLogic.firstCandleCloseStatus(bar, instrument.marketZoneId, now)
            )
            val adr = EmulatorHistoricalData.fourteenDayAdr("700", instrument).getOrThrow()
            val setup = TouchTurnLogic.computeBracketSetup(bar, TouchTurnLogic.liquidityRangeThreshold(adr))
            assertTrue(setup.isLiquidityCandle, scenario.name)
            assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar), scenario.name)
        }
    }

    @Test
    fun staticFiveMinuteHammer_confirmsForBothSides() {
        val now = 1_780_000_000_000L
        val greenBar = EmulatorStaticTouchTurnBars.openingFifteenMinuteBar(
            instrument = instrument,
            scenario = EmulatorTouchTurnScenario.GREEN_SHORT_TP,
            marketZoneId = instrument.marketZoneId,
            nowEpochMillis = now
        )
        val redBar = EmulatorStaticTouchTurnBars.openingFifteenMinuteBar(
            instrument = instrument,
            scenario = EmulatorTouchTurnScenario.RED_LONG_TP,
            marketZoneId = instrument.marketZoneId,
            nowEpochMillis = now
        )
        val config = BrokerEmulatorConfig(touchTurnScenario = EmulatorTouchTurnScenario.GREEN_SHORT_TP)
            .withTouchTurnScenarioDefaults()
        val greenHammer = EmulatorHistoricalData.fiveMinuteBarsSince(
            openingFifteenMinuteBar = greenBar,
            side = TouchTurnTradeSide.SHORT,
            config = config,
            afterBarOpenEpochMs = now,
            marketZoneId = instrument.marketZoneId,
            nowEpochMillis = now
        ).single()
        assertTrue(FiveMinuteConfirmationLogic.isHammerPattern(greenHammer, TouchTurnTradeSide.SHORT))
        assertEquals(greenBar.close, greenHammer.open, "first 5m open should match 15m close")
        val redHammer = EmulatorHistoricalData.fiveMinuteBarsSince(
            openingFifteenMinuteBar = redBar,
            side = TouchTurnTradeSide.LONG,
            config = BrokerEmulatorConfig(touchTurnScenario = EmulatorTouchTurnScenario.RED_LONG_TP)
                .withTouchTurnScenarioDefaults(),
            afterBarOpenEpochMs = now,
            marketZoneId = instrument.marketZoneId,
            nowEpochMillis = now
        ).single()
        assertTrue(FiveMinuteConfirmationLogic.isHammerPattern(redHammer, TouchTurnTradeSide.LONG))
        assertEquals(redBar.close, redHammer.open, "first 5m open should match 15m close")
        assertNotNull(greenHammer.time)
        assertNotNull(redHammer.time)
    }
}
