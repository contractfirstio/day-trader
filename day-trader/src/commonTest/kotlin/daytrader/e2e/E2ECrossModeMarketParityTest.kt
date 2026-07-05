package daytrader.e2e

import daytrader.broker.emulator.EmulatorHistoricalData
import daytrader.broker.emulator.EmulatorInstrument
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.ProgrammableIbMarketDataGateway
import daytrader.e2e.support.TouchTurnMarketFixtures
import daytrader.e2e.support.TouchTurnMarketScenarioId
import daytrader.e2e.support.applyTo
import daytrader.e2e.support.emulatorConfig
import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.BrokerId
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards cross-mode E2E parity: IB, paper, emulator, and replay must share identical
 * opening-bar OHLC and signal-context metadata for each [TouchTurnMarketScenarioId].
 */
class E2ECrossModeMarketParityTest {
    @Test
    fun canonicalScenarios_delegateThroughLegacyFixtures() {
        val nonLiquidity = TouchTurnMarketFixtures.scenario(TouchTurnMarketScenarioId.NON_LIQUIDITY)
        assertEquals(nonLiquidity.openingBar, E2ETestFixtures.nonLiquidityOpeningBar())
        assertEquals(nonLiquidity.openingBar, TouchTurnMarketFixtures.nonLiquidityOpeningBar())
    }

    @Test
    fun ibGateway_pinnedScenario_returnsCanonicalSignalContext() {
        val scenario = TouchTurnMarketFixtures.scenario(TouchTurnMarketScenarioId.RED_LIQUIDITY_LONG)
        val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        scenario.applyTo(gateway)
        assertEquals(scenario.signalContext, gateway.signalContextFetchResult.getOrThrow())
    }

    @Test
    fun paperGateway_pinnedScenario_returnsCanonicalSignalContext() {
        val scenario = TouchTurnMarketFixtures.scenario(TouchTurnMarketScenarioId.GREEN_LIQUIDITY_SHORT)
        val gateway = ProgrammableIbMarketDataGateway()
        scenario.applyTo(gateway)
        assertEquals(scenario.signalContext, gateway.bootstrapContext)
    }

    @Test
    fun emulator_pinnedScenario_returnsCanonicalOpeningBar() {
        val scenario = TouchTurnMarketFixtures.scenario(TouchTurnMarketScenarioId.NON_LIQUIDITY)
        val instrument = EmulatorInstrument(
            symbol = scenario.symbol,
            companyName = "Apple Inc.",
            currency = "USD",
            priorClose = scenario.referencePrice,
            referencePrice = scenario.referencePrice,
        )
        val bar = EmulatorHistoricalData.firstFifteenMinuteCandle(
            symbol = scenario.symbol,
            instrument = instrument,
            config = scenario.emulatorConfig(),
            nowEpochMillis = scenario.barCloseEpochMs,
        ).getOrThrow()
        assertEquals(scenario.openingBar, bar)
    }

    @Test
    fun replay_tradeLifecycle_refetchUsesCanonicalRedLiquidityBar() {
        val scenario = TouchTurnMarketFixtures.scenario(TouchTurnMarketScenarioId.TRADE_LIFECYCLE)
        val historical = ReplaySessionFixtures.tradeLifecycleContents().historicalJsonl.orEmpty()
        assertTrue(historical.contains("\"close\":${scenario.openingBar.close}"))
        assertTrue(historical.contains("\"volumeSma20\":${TouchTurnMarketFixtures.VOLUME_SMA20}"))
    }
}
