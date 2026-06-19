package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BrokerEmulatorEngineTest {

    @Test
    fun connect_publishesEmptyPositionsAndOrders() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1, historicalDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val states = events.filterIsInstance<GatewayEvent.ConnectionStateChanged>().map { it.state }
        assertTrue(GatewayConnectionState.Connected in states)

        val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        assertTrue(positions.isEmpty())

        val orders = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        assertTrue(orders.isEmpty())
    }

    @Test
    fun fetchFirstFifteenMinuteCandle_returnsBarForKnownSymbol() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(historicalDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.fetchFirstFifteenMinuteCandle(1L, "SPY")

        val ready = events.filterIsInstance<GatewayEvent.FirstFifteenMinuteCandleReady>().single()
        assertEquals(1L, ready.requestId)
        val bar = ready.result.getOrThrow()
        assertTrue(bar.high >= bar.low)
        val zone = SymbolMarkets.zoneId("SPY")
        assertEquals(
            FirstCandleCloseStatus.FORMING,
            TouchTurnLogic.firstCandleCloseStatus(bar, zone)
        )
    }

    @Test
    fun fetchFourteenDayAdr_computesFromSyntheticDailies() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(historicalDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.fetchFourteenDayAdr(2L, "QQQ")

        val ready = events.filterIsInstance<GatewayEvent.FourteenDayAdrReady>().single()
        assertEquals(2L, ready.requestId)
        assertTrue(ready.result.isSuccess)
        assertTrue(ready.result.getOrThrow() > 0.0)
    }

    @Test
    fun marketTick_withoutStreamingSubscription_isNoOp() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        events.clear()

        engine.runMarketTick()

        assertTrue(events.filterIsInstance<GatewayEvent.QuotesSnapshot>().isEmpty())
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().isEmpty())
    }

    @Test
    fun streamingLifecycle_ensurePublishesQuotes_releaseClearsSymbol() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        events.clear()

        engine.ensureStreamingMarketData("AAPL")
        val subscribed = events.filterIsInstance<GatewayEvent.QuotesSnapshot>().last().quotes
        assertTrue(subscribed.containsKey("AAPL"))

        events.clear()
        engine.releaseStreamingMarketData("AAPL")
        val released = events.filterIsInstance<GatewayEvent.QuotesSnapshot>().last().quotes
        assertTrue(!released.containsKey("AAPL"))
    }

    @Test
    fun ensureStreaming_afterResetSessionState_reseedsQuotes() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("700", referencePrice = 380.75)
        engine.resetSessionState()
        events.clear()

        engine.ensureStreamingMarketData("700", referencePrice = 380.75)

        val quotes = events.filterIsInstance<GatewayEvent.QuotesSnapshot>().last().quotes
        val quote = quotes["700"]
        assertTrue(quote != null)
        assertTrue((quote.bid ?: 0.0) > 0.0)
        assertTrue((quote.ask ?: 0.0) > (quote.bid ?: 0.0))
    }

    @Test
    fun liveIbMode_doesNotFillUntilAskCrossesBuyLimit() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!

        engine.placeTouchTurnBracket(plan)
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 104.8, ask = 105.2, last = 105.0),
            priorClose = null
        )
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 99.3, ask = 99.6, last = 99.5),
            priorClose = null
        )
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.single()
        assertTrue(position.quantity > 0)
    }

    @Test
    fun liveIbMode_fillsMarketableBuyWhenAskBelowLimit() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 0.9,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 84.8,
            stopLoss = 84.628,
            takeProfit = 85.1438
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("3690", setup, maxDollars = 500, currencyCode = "HKD")!!
        engine.placeTouchTurnBracket(plan)
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())

        engine.ingestLiveQuote(
            "3690",
            LiveQuote(symbol = "3690", bid = 83.3, ask = 83.4, last = 83.35),
            priorClose = null
        )
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.singleOrNull {
            SymbolMarkets.symbolsMatch("3690", it.symbol) && it.quantity != 0
        }
        assertTrue(position != null && position.quantity > 0, "marketable buy limit should fill when ask is below limit")
        val entryFill = events.filterIsInstance<GatewayEvent.FillsSnapshot>()
            .flatMap { it.fills }
            .first { it.parentOrderId == 0 }
        assertEquals(83.4, entryFill.price, "fill should be at live ask, not the limit")
    }

    @Test
    fun liveIbMode_fillsMarketableSellAtBidNotLimit() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 19.8,
            rangeThreshold = 14.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.SHORT,
            entry = 510.2,
            stopLoss = 525.33,
            takeProfit = 502.64
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AMD", setup, maxDollars = 10_000, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        engine.ingestLiveQuote(
            "AMD",
            LiveQuote(symbol = "AMD", bid = 518.77, ask = 519.36, last = 519.065),
            priorClose = null
        )
        val entryFill = events.filterIsInstance<GatewayEvent.FillsSnapshot>()
            .flatMap { it.fills }
            .first { it.parentOrderId == 0 }
        assertEquals(518.77, entryFill.price, "marketable sell should fill at live bid, not the limit")
    }

    @Test
    fun liveIbMode_ignoresLastOnlyUntilBidAndAskArrive() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        engine.ingestLiveQuote("AAPL", LiveQuote(symbol = "AAPL", last = 99.0), priorClose = null)
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())
    }

    @Test
    fun cancelOpenOrdersForSymbol_removesWorkingOrdersForThatSymbol() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)
        val before = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        assertTrue(before.any { SymbolMarkets.symbolsMatch("AAPL", it.symbol) })

        engine.cancelOpenOrdersForSymbol("AAPL")
        val after = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        assertTrue(after.none { SymbolMarkets.symbolsMatch("AAPL", it.symbol) })
    }

    @Test
    fun touchTurnSignalContext_bootstrapAndRefetch_shareCandleColor_perSession() = runBlocking {
        val config = BrokerEmulatorConfig(
            historicalDelayMs = 1,
            firstCandleSecondsUntilClose = 10,
            firstCandleColorMode = EmulatorFirstCandleColorMode.AUTO,
            alternateFirstCandleColor = true
        )
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(config = config, emit = { events.add(it) })

        suspend fun candleColorFromFetch(requestId: Long, isClosedBarRefetch: Boolean): FirstCandleColor {
            engine.fetchTouchTurnSignalContext(requestId, "AAPL", isClosedBarRefetch)
            val ready = events.filterIsInstance<GatewayEvent.TouchTurnSignalContextReady>().last()
            val bar = ready.result.getOrThrow().firstCandle
            return TouchTurnLogic.firstCandleColor(bar)
        }

        assertEquals(FirstCandleColor.GREEN, candleColorFromFetch(1L, isClosedBarRefetch = false))
        assertEquals(FirstCandleColor.GREEN, candleColorFromFetch(2L, isClosedBarRefetch = true))

        assertEquals(FirstCandleColor.RED, candleColorFromFetch(3L, isClosedBarRefetch = false))
        assertEquals(FirstCandleColor.RED, candleColorFromFetch(4L, isClosedBarRefetch = true))

        assertEquals(FirstCandleColor.GREEN, candleColorFromFetch(5L, isClosedBarRefetch = false))
        assertEquals(FirstCandleColor.GREEN, candleColorFromFetch(6L, isClosedBarRefetch = true))
    }

    @Test
    fun touchTurnSignalContext_refetchRetries_reuseBootstrapColor() = runBlocking {
        val config = BrokerEmulatorConfig(
            historicalDelayMs = 1,
            firstCandleSecondsUntilClose = 10,
            alternateFirstCandleColor = true
        )
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(config = config, emit = { events.add(it) })

        engine.fetchTouchTurnSignalContext(1L, "SPY", isClosedBarRefetch = false)
        val bootstrapColor = TouchTurnLogic.firstCandleColor(
            events.filterIsInstance<GatewayEvent.TouchTurnSignalContextReady>().last()
                .result.getOrThrow().firstCandle
        )

        repeat(3) { attempt ->
            engine.fetchTouchTurnSignalContext(10L + attempt, "SPY", isClosedBarRefetch = true)
            val refetchColor = TouchTurnLogic.firstCandleColor(
                events.filterIsInstance<GatewayEvent.TouchTurnSignalContextReady>().last()
                    .result.getOrThrow().firstCandle
            )
            assertEquals(bootstrapColor, refetchColor, "refetch attempt $attempt should match bootstrap")
        }
    }

    @Test
    fun closeOpenPositionForSymbol_flattensHeldPosition() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("AAPL")

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)
        engine.runMarketTick()
        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
                .any { it.symbol == "AAPL" && it.quantity != 0 }
        )

        engine.closeOpenPositionForSymbol("AAPL")
        val flat = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        assertTrue(flat.none { SymbolMarkets.symbolsMatch("AAPL", it.symbol) && it.quantity != 0 })
    }

    @Test
    fun placeWatchlistStyleBracket_withoutPriorSubscription_enablesSyntheticTicks() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.APPROACH_AND_FILL,
                touchTurnEntryMinApproachTicks = 1,
                touchTurnEntryStepPctOfRange = 0.25
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val plan = daytrader.domain.WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = "AAPL",
            currencyCode = "USD",
            instrument = null,
            side = daytrader.domain.TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
            quantity = 10
        ).getOrThrow()

        engine.placeTouchTurnBracket(plan)
        assertTrue(
            events.filterIsInstance<GatewayEvent.TouchTurnBracketPlaced>().last().ack.result.isSuccess,
            "expected bracket ack"
        )
        assertTrue(engine.shouldRunMarketTicks(), "expected synthetic ticks after bracket placement")

        var filled = false
        repeat(24) {
            engine.runMarketTick()
            val hasPosition = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().lastOrNull()
                ?.positions
                ?.any { SymbolMarkets.symbolsMatch("AAPL", it.symbol) && it.quantity != 0 } == true
            if (hasPosition) {
                filled = true
                return@repeat
            }
        }
        assertTrue(filled, "expected entry to fill after synthetic market ticks")
    }

    @Test
    fun placeTouchTurnBracket_rejectsWhenSymbolAlreadyHasOpenPosition() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 593.074,
            stopLoss = 592.3673,
            takeProfit = 594.4874
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = "NWG",
            setup = setup,
            maxDollars = 100_000,
            currencyCode = "GBP",
            instrument = daytrader.domain.InstrumentIdentity(
                symbol = "NWG",
                exchange = "SMART",
                primaryExch = "LSE",
                currency = "GBP"
            )
        )!!

        engine.placeTouchTurnBracket(plan)
        engine.ingestLiveQuote(
            "NWG",
            LiveQuote(symbol = "NWG", bid = 592.8, ask = 593.0, last = 592.9),
            priorClose = null
        )
        assertEquals(168, events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.single().quantity)

        engine.placeTouchTurnBracket(plan)
        val ack = events.filterIsInstance<GatewayEvent.TouchTurnBracketPlaced>().last().ack
        assertTrue(ack.result.isFailure)
        assertEquals(168, events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.single().quantity)
    }

}
