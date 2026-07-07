package daytrader.replay

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Quote-exhaustion stop flattens on the emulator; session stop must synchronously flatten and
 * drain before [TouchTurnEngine] reads [daytrader.gateway.BrokerGateway.fills] for persistence.
 */
class ReplaySessionStopFlattenCaptureTest {

    @Test
    fun flattenAndDrainForSessionStop_capturesFlattenFillInGatewaySnapshot() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val symbol = bundle.symbol
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(bundle, clock, scope)
        runtime.start()

        val gateway = runtime.executionGateway
        awaitConnected(gateway.connectionState.value, runtime)

        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = symbol,
            setup = TouchTurnBracketSetup(
                range = 4.0,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.SHORT,
                entry = 94.94,
                stopLoss = 98.94,
                takeProfit = 90.29488
            ),
            maxDollars = 500,
            currencyCode = "USD",
            openingBarClose = 93.0
        )!!
        gateway.placeTouchTurnBracket(plan)
        runtime.awaitEmulatorBracketPipeline()
        runtime.drainAllPendingInboundEvents()

        publishQuote(
            runtime = runtime,
            symbol = symbol,
            quote = LiveQuote(symbol = symbol, bid = 95.0, ask = 95.05, last = 95.02)
        )
        runtime.drainEmulatorPipeline()
        runtime.drainAllPendingInboundEvents()
        delay(20)
        assertEquals(1, gateway.fills.value.size, "entry fill should be recorded before flatten")

        publishQuote(
            runtime = runtime,
            symbol = symbol,
            quote = LiveQuote(symbol = symbol, bid = 90.3, ask = 90.35, last = 90.32)
        )
        runtime.drainEmulatorPipeline()
        runtime.drainAllPendingInboundEvents()

        runtime.flattenAndDrainForSessionStop(symbol)
        delay(20)

        val fills = gateway.fills.value
        assertEquals(2, fills.size, "flatten fill must appear in gateway snapshot after stop drain")
        val exitFill = fills.last()
        assertNotNull(exitFill.realizedPnL)
        assertTrue(exitFill.realizedPnL!! > 0.0, "short flatten at lower price should realize profit")
    }

    @Test
    fun asyncGatewayFlattenThenDrain_canMissExitFillBeforeSnapshot() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val symbol = bundle.symbol
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(bundle, clock, scope)
        runtime.start()

        val gateway = runtime.executionGateway
        awaitConnected(gateway.connectionState.value, runtime)

        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = symbol,
            setup = TouchTurnBracketSetup(
                range = 4.0,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.SHORT,
                entry = 94.94,
                stopLoss = 98.94,
                takeProfit = 90.29488
            ),
            maxDollars = 500,
            currencyCode = "USD",
            openingBarClose = 93.0
        )!!
        gateway.placeTouchTurnBracket(plan)
        runtime.awaitEmulatorBracketPipeline()
        runtime.drainAllPendingInboundEvents()

        publishQuote(
            runtime = runtime,
            symbol = symbol,
            quote = LiveQuote(symbol = symbol, bid = 95.0, ask = 95.05, last = 95.02)
        )
        runtime.drainEmulatorPipeline()
        runtime.drainAllPendingInboundEvents()

        publishQuote(
            runtime = runtime,
            symbol = symbol,
            quote = LiveQuote(symbol = symbol, bid = 90.3, ask = 90.35, last = 90.32)
        )
        runtime.drainEmulatorPipeline()
        runtime.drainAllPendingInboundEvents()

        gateway.flattenSymbolForSymbol(symbol)
        runtime.drainEmulatorControlQueue(maxRounds = 1)

        assertEquals(
            1,
            gateway.fills.value.size,
            "async gateway flatten + immediate partial drain reproduces missing exit fill"
        )
    }

    private suspend fun awaitConnected(
        initial: GatewayConnectionState,
        runtime: ReplayHybridRuntime
    ) {
        if (initial == GatewayConnectionState.Connected) return
        repeat(100) {
            runtime.drainAllPendingInboundEvents()
            if (runtime.executionGateway.connectionState.value == GatewayConnectionState.Connected) return
            delay(5)
        }
        assertEquals(
            GatewayConnectionState.Connected,
            runtime.executionGateway.connectionState.value,
            "emulator gateway should connect"
        )
    }

    private fun publishQuote(runtime: ReplayHybridRuntime, symbol: String, quote: LiveQuote) {
        val ingest = runtime.quoteFeeder.onCapturedQuotePublished
            ?: error("interactive replay must wire synchronous quote ingest")
        ingest(
            QuoteEvent(
                epochMs = runtime.clock.nowEpochMillis(),
                symbol = symbol,
                quote = quote
            )
        )
    }
}
