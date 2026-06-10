package daytrader.broker.emulator

import daytrader.domain.DeploymentStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.MacroTrendState
import daytrader.domain.OhlcBar
import daytrader.domain.RthMarketSessions
import daytrader.domain.StockTrendState
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class TouchTurnTrendAlignmentEmulatorTest {

    @Test
    fun emulator_fetchHomeMarketRegimeSnapshot_resolvesUkxForLondon() = runBlocking {
        val events = mutableListOf<daytrader.gateway.GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                homeMacroTrendByZone = mapOf(RthMarketSessions.EUR.zoneId to MacroTrendState.BULL)
            ),
            emit = { events.add(it) }
        )
        engine.fetchHomeMarketRegimeSnapshot(43L, RthMarketSessions.EUR.zoneId)
        val ready = events.filterIsInstance<daytrader.gateway.GatewayEvent.HomeMarketRegimeSnapshotReady>().single()
        val snapshot = ready.result.getOrThrow()
        assertEquals("UKX", snapshot.benchmark.symbol)
        assertEquals("FTSE 100", snapshot.benchmark.label)
        assertEquals(MacroTrendState.BULL, snapshot.macroTrendState())
    }

    @Test
    fun emulator_fetchHomeMarketRegimeSnapshot_resolvesHsiForHongKong() = runBlocking {
        val events = mutableListOf<daytrader.gateway.GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                homeMacroTrendByZone = mapOf(RthMarketSessions.HK.zoneId to MacroTrendState.BEAR)
            ),
            emit = { events.add(it) }
        )
        engine.fetchHomeMarketRegimeSnapshot(42L, RthMarketSessions.HK.zoneId)
        val ready = events.filterIsInstance<daytrader.gateway.GatewayEvent.HomeMarketRegimeSnapshotReady>().single()
        val snapshot = ready.result.getOrThrow()
        assertEquals("HSI", snapshot.benchmark.symbol)
        assertEquals(MacroTrendState.BEAR, snapshot.macroTrendState())
    }

    @Test
    fun emulator_defaultConfig_alignsTrendMocksToSessionCandleColor() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                alternateFirstCandleColor = true
            ),
            emit = { events.add(it) }
        )

        repeat(3) { attempt ->
            engine.fetchTouchTurnSignalContext(
                requestId = attempt.toLong(),
                symbol = "0700",
                isClosedBarRefetch = false
            )
        }
        val greenBar = events.filterIsInstance<GatewayEvent.TouchTurnSignalContextReady>().last()
            .result.getOrThrow().firstCandle
        assertEquals(FirstCandleColor.GREEN, TouchTurnLogic.firstCandleColor(greenBar))

        engine.fetchHomeMarketRegimeSnapshot(100L, RthMarketSessions.HK.zoneId)
        val macro = events.filterIsInstance<GatewayEvent.HomeMarketRegimeSnapshotReady>().last()
            .result.getOrThrow()
        engine.fetchReversalScoreSymbolSnapshot(101L, "0700", instrument = null)
        val reversal = events.filterIsInstance<GatewayEvent.ReversalScoreSymbolSnapshotReady>().last()
            .result.getOrThrow()
        val stock = daytrader.domain.StockTrendEvaluator.buildSnapshot(
            reversal.live.lastPrice,
            reversal.historical.dailyCloses
        ).getOrThrow()

        assertEquals(MacroTrendState.BEAR, macro.macroTrendState())
        assertEquals(StockTrendState.DOWN, stock.stockTrendState())
    }

    @Test
    fun emulatorGateway_greenCandle_defaultConfig_permitsShort() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = EmulatorModeTestHarness(
            scope = scope,
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                alternateFirstCandleColor = true
            )
        )
        harness.start()

        repeat(3) { attempt ->
            harness.gateway.fetchTouchTurnSignalContext("0700", isClosedBarRefetch = false)
        }
        val macro = harness.gateway.fetchHomeMarketRegimeSnapshot(RthMarketSessions.HK.zoneId).getOrThrow()
        val stock = harness.gateway.fetchStockTrendSnapshot("700").getOrThrow()
        assertEquals(MacroTrendState.BEAR, macro.macroTrendState())
        assertEquals(StockTrendState.DOWN, stock.stockTrendState())

        val bar = greenLiquidityBar()
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, RthMarketSessions.HK.zoneId)!!
        val gate = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            volumeSma20 = 300_000.0,
            marketZoneId = RthMarketSessions.HK.zoneId,
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2026-06-10",
            enforceCloseConfirmation = false,
            liveBid = null,
            liveAsk = null,
            liveLast = null,
            requireLivePriceChecks = false,
            macroTrend = macro.macroTrendState(),
            stockTrend = stock.stockTrendState(),
            rules = trendRules()
        )
        assertEquals(null, gate.decisionOutcome, "unexpected block: ${gate.decisionOutcome}")
        assertTrue(gate.entryOrdersPermitted, "expected entry permitted")

        harness.shutdown()
    }

    @Test
    fun emulatorGateway_hkAlignedTrends_permitRedLong() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = EmulatorModeTestHarness(scope, trendAlignedEmulatorConfig())
        harness.start()

        val macro = harness.gateway.fetchHomeMarketRegimeSnapshot(RthMarketSessions.HK.zoneId).getOrThrow()
        val stock = harness.gateway.fetchStockTrendSnapshot("700").getOrThrow()
        assertEquals("HSI", macro.benchmark.symbol)
        assertEquals("Hang Seng", macro.benchmark.label)
        assertEquals(MacroTrendState.BULL, macro.macroTrendState())
        assertEquals(StockTrendState.UP, stock.stockTrendState())

        val bar = redLiquidityBar()
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, RthMarketSessions.HK.zoneId)!!
        val gate = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            volumeSma20 = 300_000.0,
            marketZoneId = RthMarketSessions.HK.zoneId,
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2026-06-04",
            enforceCloseConfirmation = false,
            liveBid = null,
            liveAsk = null,
            liveLast = null,
            requireLivePriceChecks = false,
            macroTrend = macro.macroTrendState(),
            stockTrend = stock.stockTrendState(),
            rules = trendRules()
        )
        assertEquals(null, gate.decisionOutcome, "unexpected block: ${gate.decisionOutcome}")
        assertTrue(gate.entryOrdersPermitted, "expected entry permitted")

        harness.shutdown()
    }

    @Test
    fun emulatorGateway_hkBearMacro_blocksRedLong() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = EmulatorModeTestHarness(
            scope = scope,
            config = trendAlignedEmulatorConfig(
                homeMacroTrendByZone = mapOf(RthMarketSessions.HK.zoneId to MacroTrendState.BEAR)
            )
        )
        harness.start()

        val macro = harness.gateway.fetchHomeMarketRegimeSnapshot(RthMarketSessions.HK.zoneId).getOrThrow()
        val stock = harness.gateway.fetchStockTrendSnapshot("700").getOrThrow()
        assertEquals("HSI", macro.benchmark.symbol)
        assertEquals(MacroTrendState.BEAR, macro.macroTrendState())

        val bar = redLiquidityBar()
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, RthMarketSessions.HK.zoneId)!!
        val gate = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            volumeSma20 = 300_000.0,
            marketZoneId = RthMarketSessions.HK.zoneId,
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2026-06-04",
            enforceCloseConfirmation = false,
            liveBid = null,
            liveAsk = null,
            liveLast = null,
            requireLivePriceChecks = false,
            macroTrend = macro.macroTrendState(),
            stockTrend = stock.stockTrendState(),
            rules = trendRules()
        )
        assertFalse(gate.entryOrdersPermitted)
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_MISALIGNED, gate.decisionOutcome)

        harness.shutdown()
    }

    @Test
    fun withLiquidityEvaluated_storesHsiBenchmarkForHkDeployment() {
        val bar = redLiquidityBar()
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, RthMarketSessions.HK.zoneId)!! + 1_000
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 5_000,
            marketZoneId = RthMarketSessions.HK.zoneId,
            currencyCode = "HKD",
            status = DeploymentStatus.RUNNING,
            brokerKind = BrokerKind.EMULATOR
        )
            .copy(touchTurnRules = trendRules())
            .let { it.copy(touchTurnSession = daytrader.domain.TouchTurnSessionContext(
                sessionDate = "2026-06-04",
                status = daytrader.domain.TouchTurnCandleStatus.READY,
                candle = bar,
                marketZoneId = RthMarketSessions.HK.zoneId,
                currencyCode = "HKD",
                atr14 = 4.5,
                volumeSma20 = 300_000.0,
                rangeThreshold = 1.0,
                rules = trendRules()
            )) }

        val evaluated = deployment.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = barEnd,
            macroTrend = MacroTrendState.BULL,
            stockTrend = StockTrendState.UP,
            macroBenchmarkSymbol = "HSI",
            macroBenchmarkLabel = "Hang Seng"
        )

        val session = evaluated.touchTurnSession!!
        assertEquals("HSI", session.macroBenchmarkSymbol)
        assertEquals("Hang Seng", session.macroBenchmarkLabel)
        assertEquals(true, session.entryOrdersPermitted)
    }

    private fun redLiquidityBar(): OhlcBar = OhlcBar(
        open = 382.0,
        high = 383.0,
        low = 375.0,
        close = 376.5,
        time = "20260604  09:30:00",
        volume = 350_000.0
    )

    private fun greenLiquidityBar(): OhlcBar = OhlcBar(
        open = 375.0,
        high = 383.0,
        low = 375.0,
        close = 376.5,
        time = "20260610  11:45:27",
        volume = 350_000.0
    )

    private fun trendAlignedEmulatorConfig(
        homeMacroTrendByZone: Map<String, MacroTrendState> = mapOf(
            RthMarketSessions.HK.zoneId to MacroTrendState.BULL
        )
    ) = BrokerEmulatorConfig(
        connectDelayMs = 1,
        homeMacroTrendByZone = homeMacroTrendByZone,
        stockTrendBySymbol = mapOf("700" to StockTrendState.UP)
    )

    private fun trendRules() = TouchTurnRuleConfig.DEFAULT.copy(
        enables = TouchTurnRuleEnables.DEFAULT.copy(
            macroTrendAlignment = true,
            stockTrendAlignment = true,
            barCloseTurn = false,
            entryWindow = false,
            volumeExhaustion = false
        )
    )
}
