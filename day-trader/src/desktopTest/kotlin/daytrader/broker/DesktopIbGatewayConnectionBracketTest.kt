package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.EJavaSignal
import com.ib.client.EWrapper
import com.ib.client.Order
import com.ib.client.OrderState
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderPlan
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
import java.lang.reflect.Constructor
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

    @Test
    fun resizeTouchTurnBracket_acksAfterChildOrderStatus() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            seedWorkingBracket(harness, orderIdBase = 500, quantity = 5)

            val orderIds = TouchTurnBracketOrderIds(
                parentOrderId = 500,
                takeProfitOrderId = 501,
                stopLossOrderId = 502,
                adjustableStopOrderId = null,
            )
            val resizePlan = planWithQuantity(threeLegLiquidityPlan(), quantity = 10)
            harness.queues.outbound.offer(
                GatewayCommand.ResizeTouchTurnBracket(
                    requestId = 42L,
                    request = TouchTurnBracketResizeRequest(
                        symbol = E2ETestFixtures.SYMBOL,
                        currencyCode = "USD",
                        instrument = null,
                        orderIds = orderIds,
                        plan = resizePlan,
                    ),
                )
            )
            delay(500)

            assertEquals(3, harness.recordingClient.placedOrders.size)
            assertTrue(
                harness.recordingClient.placedOrders.all {
                    it.order.totalQuantity().longValue() == 10L
                }
            )

            harness.connection.orderStatus(
                orderId = 501,
                status = "Submitted",
                filled = Decimal.ZERO,
                remaining = Decimal.get(10),
                avgFillPrice = 0.0,
                permId = 1L,
                parentId = 500,
                lastFillPrice = 0.0,
                clientId = config.clientId,
                whyHeld = "",
                mktCapPrice = 0.0,
            )

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketResized
            } as GatewayEvent.TouchTurnBracketResized

            assertEquals(42L, event.requestId)
            assertTrue(event.result.isSuccess)
            assertEquals(10, event.result.getOrNull())
        }
    }

    @Test
    fun resizeTouchTurnBracket_whenEntryPartiallyFilled_rejectsBeforeTransmit() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            seedWorkingBracket(harness, orderIdBase = 500, quantity = 5, entryFilled = 1)

            val orderIds = TouchTurnBracketOrderIds(500, 501, 502, null)
            harness.queues.outbound.offer(
                GatewayCommand.ResizeTouchTurnBracket(
                    requestId = 7L,
                    request = TouchTurnBracketResizeRequest(
                        symbol = E2ETestFixtures.SYMBOL,
                        currencyCode = "USD",
                        instrument = null,
                        orderIds = orderIds,
                        plan = planWithQuantity(threeLegLiquidityPlan(), quantity = 10),
                    ),
                )
            )
            delay(300)

            assertTrue(harness.recordingClient.placedOrders.isEmpty())

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketResized
            } as GatewayEvent.TouchTurnBracketResized

            assertTrue(event.result.isFailure)
            assertEquals("entry_already_filled", event.result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun resizeTouchTurnBracket_openOrderAtTargetQuantity_acksWithoutOrderStatus() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            seedWorkingBracket(harness, orderIdBase = 500, quantity = 100)

            val orderIds = TouchTurnBracketOrderIds(500, 501, 502, null)
            harness.queues.outbound.offer(
                GatewayCommand.ResizeTouchTurnBracket(
                    requestId = 12L,
                    request = TouchTurnBracketResizeRequest(
                        symbol = E2ETestFixtures.SYMBOL,
                        currencyCode = "USD",
                        instrument = null,
                        orderIds = orderIds,
                        plan = planWithQuantity(threeLegLiquidityPlan(), quantity = 200),
                    ),
                )
            )
            delay(500)

            val contract = Contract().also {
                it.symbol(E2ETestFixtures.SYMBOL)
                it.secType("STK")
                it.exchange("SMART")
                it.currency("USD")
            }
            val entry = Order().also {
                it.orderId(500)
                it.permId(1_500L)
                it.action("BUY")
                it.orderType("LMT")
                it.totalQuantity(Decimal.get(200))
                it.filledQuantity(Decimal.ZERO)
                it.lmtPrice(100.0)
                it.transmit(false)
            }
            harness.connection.openOrder(500, contract, entry, submittedOrderState())

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketResized
            } as GatewayEvent.TouchTurnBracketResized

            assertEquals(12L, event.requestId)
            assertTrue(event.result.isSuccess)
            assertEquals(200, event.result.getOrNull())
        }
    }

    @Test
    fun resizeTouchTurnBracket_staleChildQuantity_doesNotAckUntilTargetQuantity() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            seedWorkingBracket(harness, orderIdBase = 500, quantity = 5)

            val orderIds = TouchTurnBracketOrderIds(500, 501, 502, null)
            harness.queues.outbound.offer(
                GatewayCommand.ResizeTouchTurnBracket(
                    requestId = 11L,
                    request = TouchTurnBracketResizeRequest(
                        symbol = E2ETestFixtures.SYMBOL,
                        currencyCode = "USD",
                        instrument = null,
                        orderIds = orderIds,
                        plan = planWithQuantity(threeLegLiquidityPlan(), quantity = 10),
                    ),
                )
            )
            delay(500)

            harness.connection.orderStatus(
                orderId = 501,
                status = "Submitted",
                filled = Decimal.ZERO,
                remaining = Decimal.get(5),
                avgFillPrice = 0.0,
                permId = 1_501L,
                parentId = 500,
                lastFillPrice = 0.0,
                clientId = config.clientId,
                whyHeld = "",
                mktCapPrice = 0.0,
            )
            delay(200)
            assertTrue(
                harness.queues.inbound.none { it is GatewayEvent.TouchTurnBracketResized },
                "Stale child quantity must not ack resize",
            )

            harness.connection.orderStatus(
                orderId = 501,
                status = "Submitted",
                filled = Decimal.ZERO,
                remaining = Decimal.get(10),
                avgFillPrice = 0.0,
                permId = 1_501L,
                parentId = 500,
                lastFillPrice = 0.0,
                clientId = config.clientId,
                whyHeld = "",
                mktCapPrice = 0.0,
            )

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketResized
            } as GatewayEvent.TouchTurnBracketResized
            assertTrue(event.result.isSuccess)
        }
    }

    @Test
    fun resizeTouchTurnBracket_orderError_emitsFailure() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            seedWorkingBracket(harness, orderIdBase = 500, quantity = 5)

            val orderIds = TouchTurnBracketOrderIds(500, 501, 502, null)
            harness.queues.outbound.offer(
                GatewayCommand.ResizeTouchTurnBracket(
                    requestId = 9L,
                    request = TouchTurnBracketResizeRequest(
                        symbol = E2ETestFixtures.SYMBOL,
                        currencyCode = "USD",
                        instrument = null,
                        orderIds = orderIds,
                        plan = planWithQuantity(threeLegLiquidityPlan(), quantity = 10),
                    ),
                )
            )
            delay(500)

            harness.connection.error(
                reqId = 501,
                errorTime = System.currentTimeMillis(),
                errorCode = 201,
                errorMsg = "Order rejected - reason",
                advancedOrderRejectJson = null,
            )

            val event = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketResized && it.result.isFailure
            } as GatewayEvent.TouchTurnBracketResized

            assertEquals(9L, event.requestId)
            assertTrue(event.result.exceptionOrNull()?.message?.contains("ib_order_error") == true)
        }
    }

    @Test
    fun resizeTouchTurnBracket_success_doesNotClearOpenOrderTemplates() = runBlocking {
        // Production: emitTouchTurnBracketResizeSuccess used requestOpenOrders() with default
        // clearCache=true, wiping templates mid auto-flush burst (39→0 snapshot).
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            // Skip nextValidId — it schedules requestOpenOrders(clearCache=true).
            seedWorkingBracket(harness, orderIdBase = 500, quantity = 5)

            val openOrdersRequestsBefore = harness.recordingClient.reqAllOpenOrdersCount

            harness.queues.outbound.offer(
                GatewayCommand.ResizeTouchTurnBracket(
                    requestId = 77L,
                    request = TouchTurnBracketResizeRequest(
                        symbol = E2ETestFixtures.SYMBOL,
                        currencyCode = "USD",
                        instrument = null,
                        orderIds = TouchTurnBracketOrderIds(500, 501, 502, null),
                        plan = planWithQuantity(threeLegLiquidityPlan(), quantity = 10),
                    ),
                )
            )
            delay(300)
            harness.connection.orderStatus(
                orderId = 501,
                status = "Submitted",
                filled = Decimal.ZERO,
                remaining = Decimal.get(10),
                avgFillPrice = 0.0,
                permId = 1_501L,
                parentId = 500,
                lastFillPrice = 0.0,
                clientId = config.clientId,
                whyHeld = "",
                mktCapPrice = 0.0,
            )

            val resized = harness.awaitInbound(timeoutMs = 3_000) {
                it is GatewayEvent.TouchTurnBracketResized
            } as GatewayEvent.TouchTurnBracketResized
            assertTrue(resized.result.isSuccess)
            assertEquals(
                openOrdersRequestsBefore,
                harness.recordingClient.reqAllOpenOrdersCount,
                "Resize success must not requestOpenOrders (clears sibling templates mid flush)",
            )
        }
    }

    private fun planWithQuantity(plan: TouchTurnOrderPlan, quantity: Int): TouchTurnOrderPlan =
        plan.copy(
            quantity = quantity,
            orders = plan.orders.map { it.copy(quantity = quantity) },
        )

    private fun seedWorkingBracket(
        harness: Harness,
        orderIdBase: Int,
        quantity: Int,
        entryFilled: Int = 0,
        symbol: String = E2ETestFixtures.SYMBOL,
    ) {
        val contract = Contract().also {
            it.symbol(symbol)
            it.secType("STK")
            it.exchange("SMART")
            it.currency("USD")
        }
        val entry = Order().also {
            it.orderId(orderIdBase)
            it.permId(1_000L + orderIdBase)
            it.action("BUY")
            it.orderType("LMT")
            it.totalQuantity(Decimal.get(quantity.toLong()))
            it.filledQuantity(Decimal.get(entryFilled.toLong()))
            it.lmtPrice(100.0)
            it.transmit(false)
        }
        val takeProfit = Order().also {
            it.orderId(orderIdBase + 1)
            it.permId(1_001L + orderIdBase)
            it.parentId(orderIdBase)
            it.action("SELL")
            it.orderType("LMT")
            it.totalQuantity(Decimal.get(quantity.toLong()))
            it.lmtPrice(101.0)
            it.transmit(false)
        }
        val stopLoss = Order().also {
            it.orderId(orderIdBase + 2)
            it.permId(1_002L + orderIdBase)
            it.parentId(orderIdBase)
            it.action("SELL")
            it.orderType("STP")
            it.totalQuantity(Decimal.get(quantity.toLong()))
            it.auxPrice(99.0)
            it.transmit(true)
        }
        val state = submittedOrderState()
        harness.connection.openOrder(orderIdBase, contract, entry, state)
        harness.connection.openOrder(orderIdBase + 1, contract, takeProfit, state)
        harness.connection.openOrder(orderIdBase + 2, contract, stopLoss, state)
    }

    private fun submittedOrderState(): OrderState {
        val ctor: Constructor<OrderState> = OrderState::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance().apply {
            status("Submitted")
        }
    }

    private fun threeLegLiquidityPlan(symbol: String = E2ETestFixtures.SYMBOL) =
        TouchTurnOrderPlanner.buildOrderPlan(
            symbol = symbol,
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
        @Volatile var reqAllOpenOrdersCount: Int = 0

        override fun isConnected(): Boolean = connected

        override fun placeOrder(orderId: Int, contract: Contract, order: Order) {
            placedOrders.add(PlacedOrder(orderId, contract, order))
        }

        override fun reqAllOpenOrders() {
            reqAllOpenOrdersCount++
        }
    }
}
