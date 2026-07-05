package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.EJavaSignal
import com.ib.client.EWrapper
import com.ib.client.Order
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnTradeSide
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.gateway.BlockingGatewayQueues
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Tier 2: exercises [DesktopIbGatewayConnection] Touch Turn bracket command handling
 * (IB BDD mocks stop at [daytrader.engine.support.FakeBrokerGateway]).
 */
class DesktopIbGatewayConnectionBracketTest {
    private val config = IbGatewayConfig(host = "127.0.0.1", port = 4002, clientId = 7, accountCode = "DU123")

    @Test
    fun marketDataOnlyConnection_rejectsBracketPlacement() = runBlocking {
        withHarness(connectionMode = IbConnectionMode.MARKET_DATA_ONLY) { harness ->
            harness.connection.start()
            harness.queues.outbound.offer(GatewayCommand.PlaceTouchTurnBracket(E2EBracketHelper.liquidityPlan()))

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketPlaced
            } as GatewayEvent.TouchTurnBracketPlaced

            assertTrue(event.ack.result.isFailure)
            assertEquals("market_data_only_connection", event.ack.result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun placeTouchTurnBracket_withoutNextValidId_emitsBuildFailure() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.queues.outbound.offer(GatewayCommand.PlaceTouchTurnBracket(E2EBracketHelper.liquidityPlan()))
            delay(300)

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketPlaced
            } as GatewayEvent.TouchTurnBracketPlaced

            assertTrue(event.ack.result.isFailure)
            assertEquals("bracket_build_failed", event.ack.result.exceptionOrNull()?.message)
            assertTrue(harness.recordingClient.placedOrders.isEmpty())
        }
    }

    @Test
    fun placeTouchTurnBracket_placesThreeLegsAndAcksAfterChildOpenOrder() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            val plan = threeLegLiquidityPlan()
            harness.queues.outbound.offer(GatewayCommand.PlaceTouchTurnBracket(plan))
            delay(500)

            assertEquals(3, harness.recordingClient.placedOrders.size)
            val placedIds = harness.recordingClient.placedOrders.map { it.orderId }.toSet()
            assertEquals(setOf(500, 501, 502), placedIds)

            val takeProfitId = 501
            harness.connection.orderStatus(
                orderId = takeProfitId,
                status = "Submitted",
                filled = Decimal.ZERO,
                remaining = Decimal.get(plan.quantity.toLong()),
                avgFillPrice = 0.0,
                permId = 1L,
                parentId = 500,
                lastFillPrice = 0.0,
                clientId = config.clientId,
                whyHeld = "",
                mktCapPrice = 0.0,
            )

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketPlaced && it.ack.result.isSuccess
            } as GatewayEvent.TouchTurnBracketPlaced

            assertEquals("AAPL", event.ack.symbol)
            assertEquals(listOf(500, 501, 502), event.ack.orderIds)
            assertTrue(event.ack.result.isSuccess)
        }
    }

    @Test
    fun placeTouchTurnBracket_whenDisconnectedAtBuild_emitsBuildFailure() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = false
            harness.connection.start()
            harness.connection.nextValidId(500)
            harness.queues.outbound.offer(GatewayCommand.PlaceTouchTurnBracket(E2EBracketHelper.liquidityPlan()))
            delay(300)

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketPlaced
            } as GatewayEvent.TouchTurnBracketPlaced

            assertFalse(event.ack.result.isSuccess)
            assertEquals("bracket_build_failed", event.ack.result.exceptionOrNull()?.message)
        }
    }

    private fun threeLegLiquidityPlan() = TouchTurnOrderPlanner.buildOrderPlan(
        symbol = E2ETestFixtures.SYMBOL,
        setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0,
        ),
        maxDollars = 500,
        currencyCode = "USD",
        openingBarClose = 100.85,
        rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(adjustableTrailingStop = false),
        ),
    )!!

    private suspend fun withHarness(
        connectionMode: IbConnectionMode = IbConnectionMode.FULL,
        block: suspend (Harness) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queues = BlockingGatewayQueues()
        lateinit var recordingClient: RecordingEClientSocket
        val connection = DesktopIbGatewayConnection(
            queues = queues,
            config = config,
            connectionMode = connectionMode,
            scope = scope,
            clientFactory = { wrapper ->
                RecordingEClientSocket(wrapper).also { recordingClient = it }
            },
        )
        val harness = Harness(queues, connection, recordingClient)
        try {
            block(harness)
        } finally {
            queues.outbound.offer(GatewayCommand.Shutdown)
            delay(50)
            scope.cancel()
        }
    }

    private class Harness(
        val queues: BlockingGatewayQueues,
        val connection: DesktopIbGatewayConnection,
        val recordingClient: RecordingEClientSocket,
    ) {
        suspend fun awaitInbound(
            timeoutMs: Long,
            predicate: (GatewayEvent) -> Boolean,
        ): GatewayEvent {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val event = queues.inbound.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (predicate(event)) return event
            }
            error("Timed out waiting for inbound event matching predicate")
        }
    }

    private class RecordingEClientSocket(wrapper: EWrapper) : EClientSocket(wrapper, EJavaSignal()) {
        data class PlacedOrder(val orderId: Int, val contract: Contract, val order: Order)

        val placedOrders = mutableListOf<PlacedOrder>()
        @Volatile var connected: Boolean = false

        override fun isConnected(): Boolean = connected

        override fun placeOrder(orderId: Int, contract: Contract, order: Order) {
            placedOrders.add(PlacedOrder(orderId, contract, order))
        }
    }
}
