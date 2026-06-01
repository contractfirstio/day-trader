package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.QuoteSource
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class EmulatorBrokerAdapterPriorityTest {

    @Test
    fun bracketsDuringQuoteFlood_emitAckAndOpenOrders() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val outbound = LinkedBlockingQueue<GatewayCommand>()
        val bus = MarketQuoteBus()
        val adapter = EmulatorBrokerAdapter(
            emit = { events.add(it) },
            receiveCommand = { outbound.take() },
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            quoteBus = bus
        )
        adapter.start()
        outbound.offer(GatewayCommand.Connect)
        while (events.filterIsInstance<GatewayEvent.ConnectionStateChanged>().none {
                it.state == GatewayConnectionState.Connected
            }
        ) {
            delay(5)
        }

        val quoteFlood = launch(Dispatchers.Default) {
            repeat(500) { tick ->
                listOf("700", "9988", "1810", "3690").forEach { symbol ->
                    bus.publish(
                        symbol = symbol,
                        quote = LiveQuote(
                            symbol = symbol,
                            bid = 100.0 + tick * 0.01,
                            ask = 100.2 + tick * 0.01,
                            last = 100.1 + tick * 0.01
                        ),
                        priorClose = null,
                        source = QuoteSource.EXTERNAL
                    )
                }
            }
        }

        val symbols = listOf("00700", "09988", "01810", "03690")
        symbols.forEach { symbol ->
            val plan = touchTurnPlan(symbol) ?: error("plan for $symbol")
            outbound.offer(GatewayCommand.PlaceTouchTurnBracket(plan))
        }
        quoteFlood.join()
        delay(500)

        val acks = events.filterIsInstance<GatewayEvent.TouchTurnBracketPlaced>()
        assertEquals(symbols.size, acks.size, "acks=$acks")
        assertTrue(acks.all { it.ack.result.isSuccess }, "acks=$acks")

        val latestOrders = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        val entryOrders = latestOrders.filter { it.parentOrderId == 0 }
        assertEquals(symbols.size, entryOrders.size, "orders=$latestOrders")
        adapter.shutdown()
    }

    @Test
    fun multipleBrackets_completeWhileMarketTicksAndOrderProgressRun() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val outbound = LinkedBlockingQueue<GatewayCommand>()
        val adapter = EmulatorBrokerAdapter(
            emit = { events.add(it) },
            receiveCommand = { outbound.take() },
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(
                connectDelayMs = 1,
                marketTickIntervalMs = 1,
                orderProgressIntervalMs = 1
            )
        )
        adapter.start()
        outbound.offer(GatewayCommand.Connect)
        while (events.filterIsInstance<GatewayEvent.ConnectionStateChanged>().none {
                it.state == GatewayConnectionState.Connected
            }
        ) {
            delay(5)
        }
        delay(50)

        val symbols = listOf("LLOY", "JD.", "VOD", "GLEN", "BP.", "BTRW", "BARC", "RR.")
        symbols.forEach { symbol ->
            val plan = touchTurnPlan(symbol) ?: error("plan for $symbol")
            outbound.offer(GatewayCommand.PlaceTouchTurnBracket(plan))
        }
        delay(500)

        val acks = events.filterIsInstance<GatewayEvent.TouchTurnBracketPlaced>()
        assertEquals(symbols.size, acks.size, "acks=${acks.map { it.ack.symbol }}")
        assertTrue(acks.all { it.ack.result.isSuccess })
        adapter.shutdown()
    }

    @Test
    fun throwingLiveQuotesCallback_doesNotKillOrderActorForNextBracket() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val outbound = LinkedBlockingQueue<GatewayCommand>()
        var subscribeAttempts = 0
        val adapter = EmulatorBrokerAdapter(
            emit = { events.add(it) },
            receiveCommand = { outbound.take() },
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            onSymbolNeedsLiveQuotes = {
                subscribeAttempts++
                error("simulated IB subscribe failure")
            }
        )
        adapter.start()
        outbound.offer(GatewayCommand.Connect)
        while (events.filterIsInstance<GatewayEvent.ConnectionStateChanged>().none {
                it.state == GatewayConnectionState.Connected
            }
        ) {
            delay(5)
        }
        delay(50)

        val symbols = listOf("LLOY", "JD.")
        symbols.forEach { symbol ->
            val plan = touchTurnPlan(symbol) ?: error("plan for $symbol")
            outbound.offer(GatewayCommand.PlaceTouchTurnBracket(plan))
        }
        delay(500)

        val acks = events.filterIsInstance<GatewayEvent.TouchTurnBracketPlaced>()
        assertEquals(symbols.size, acks.size, "acks=${acks.map { it.ack.symbol }}")
        assertTrue(acks.all { it.ack.result.isSuccess }, "acks=$acks")
        assertEquals(symbols.size, subscribeAttempts, "expected subscribe per symbol")
        adapter.shutdown()
    }

    @Test
    fun bracketPlacement_doesNotBlockExternalQuoteIngestion() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val outbound = LinkedBlockingQueue<GatewayCommand>()
        val bus = MarketQuoteBus()
        val adapter = EmulatorBrokerAdapter(
            emit = { events.add(it) },
            receiveCommand = { outbound.take() },
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            quoteBus = bus
        )
        adapter.start()
        outbound.offer(GatewayCommand.Connect)
        while (events.filterIsInstance<GatewayEvent.ConnectionStateChanged>().none {
                it.state == GatewayConnectionState.Connected
            }
        ) {
            delay(5)
        }

        val quoteFlood = launch(Dispatchers.Default) {
            var tick = 0
            while (tick < 200) {
                bus.publish(
                    symbol = "BTRW",
                    quote = LiveQuote(symbol = "BTRW", bid = 100.0 + tick, ask = 100.2 + tick, last = 100.1 + tick),
                    priorClose = null,
                    source = QuoteSource.EXTERNAL
                )
                tick++
            }
        }

        val plan = touchTurnPlan("BTRW") ?: error("plan")
        outbound.offer(GatewayCommand.PlaceTouchTurnBracket(plan))
        quoteFlood.join()
        delay(300)

        assertTrue(
            events.any { it is GatewayEvent.TouchTurnBracketPlaced && it.ack.symbol == "BTRW" },
            "expected bracket ack for BTRW"
        )
        adapter.shutdown()
    }

    private fun touchTurnPlan(symbol: String) = TouchTurnOrderPlanner.buildOrderPlan(
        symbol = symbol,
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
        currencyCode = if (symbol.all { it.isDigit() }) "HKD" else "USD"
    )
}
