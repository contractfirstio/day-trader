package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Regression guard for hybrid paper execution ([BrokerEmulatorConfig.forLiveIbMarketData]).
 * Live-exchange fills follow real limit-order rules (buy at ask, sell at bid) on each quote tick.
 */
class HybridProductionEmulatorTest {

    @Test
    fun forLiveIbMarketData_usesApproachAndFill_notImmediateEntryOnBracketPlace() = runBlocking {
        val config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1)
        assertEquals(EmulatorPricingSource.LIVE_EXCHANGE, config.pricingSource)
        assertEquals(false, config.touchTurnEntryFillImmediately)
        assertEquals(null, config.touchTurnEntryScenarioOverride)
        assertEquals(0.0, config.touchTurnEntryNeverFillProbability)

        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(config = config, emit = { events.add(it) })
        engine.handleConnect()
        engine.finishConnect()

        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = "AAPL",
            setup = TouchTurnBracketSetup(
                range = 2.0,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.GREEN,
                side = TouchTurnTradeSide.LONG,
                entry = 100.0,
                stopLoss = 99.0,
                takeProfit = 101.0
            ),
            maxDollars = 500,
            currencyCode = "USD"
        )!!

        engine.placeTouchTurnBracket(plan)

        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "production hybrid must not fill entry on bracket place"
        )
        val entryWorking = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
            .any { it.symbol == "AAPL" && it.parentOrderId == 0 && it.remaining > 0 }
        assertTrue(entryWorking, "entry limit should remain working until live quote crosses")

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 100.5, ask = 101.0, last = 100.75),
            priorClose = null
        )
        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "production hybrid must not fill when ask is still above buy limit"
        )

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 99.3, ask = 99.6, last = 99.5),
            priorClose = null
        )
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.single()
        assertTrue(position.quantity > 0, "entry should fill once live ask crosses buy limit")
    }

    @Test
    fun forLiveIbMarketData_marketableLimitAtPlacement_fillsOnFirstQuote() = runBlocking {
        val config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1)
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(config = config, emit = { events.add(it) })
        engine.handleConnect()
        engine.finishConnect()

        val entry = 26.5104
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = "1810",
            setup = TouchTurnBracketSetup(
                range = 0.5,
                rangeThreshold = 0.1,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.LONG,
                entry = entry,
                stopLoss = entry - 0.1,
                takeProfit = entry + 0.2
            ),
            maxDollars = 500,
            currencyCode = "HKD"
        )!!

        engine.ingestLiveQuote(
            "1810",
            LiveQuote(symbol = "1810", bid = 26.42, ask = 26.44, last = 26.44),
            priorClose = null
        )
        engine.placeTouchTurnBracket(plan)

        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "entry must not fill on bracket place before the next quote evaluation"
        )

        engine.ingestLiveQuote(
            "1810",
            LiveQuote(symbol = "1810", bid = 26.40, ask = 26.42, last = 26.42),
            priorClose = null
        )
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.single()
        assertTrue(
            position.quantity > 0,
            "marketable buy limit should fill once live ask is at or below the limit"
        )
    }
}
