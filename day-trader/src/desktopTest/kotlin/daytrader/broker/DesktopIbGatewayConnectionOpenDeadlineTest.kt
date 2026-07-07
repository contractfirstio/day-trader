package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.EJavaSignal
import com.ib.client.EWrapper
import com.ib.client.Order
import com.ib.client.OrderCancel
import com.ib.client.OrderState
import java.lang.reflect.Constructor
import daytrader.data.SessionOrderClassification
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.gateway.BlockingGatewayQueues
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * IB gateway open-deadline exit: preserve GTC stop-loss on partial cancel, and market-close
 * using explicit broker snapshot qty when the internal position cache is empty.
 */
class DesktopIbGatewayConnectionOpenDeadlineTest {
    private val config = IbGatewayConfig(host = "127.0.0.1", port = 4002, clientId = 7, accountCode = "DU123")

    @Test
    fun cancelOpenOrders_preserveStopLoss_keepsStopCancelsEntryAndTakeProfit() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(500)
            harness.queues.outbound.offer(GatewayCommand.PlaceTouchTurnBracket(E2EBracketHelper.liquidityPlan()))
            delay(500)
            harness.recordingClient.placedOrders.forEach { placed ->
                harness.connection.openOrder(
                    placed.orderId,
                    placed.contract,
                    placed.order,
                    submittedOrderState()
                )
            }
            delay(200)

            harness.queues.outbound.offer(
                GatewayCommand.CancelOpenOrdersForSymbol(
                    symbol = E2ETestFixtures.SYMBOL,
                    preserveStopLoss = true
                )
            )
            delay(400)

            val stopOrderIds = harness.recordingClient.placedOrders
                .filter { placed ->
                    val type = placed.order.getOrderType().orEmpty().uppercase()
                    type == "STP" || type == "TRAIL" || type.startsWith("STP ")
                }
                .map { it.orderId }
                .toSet()
            assertTrue(stopOrderIds.isNotEmpty(), "expected a protective stop order in bracket")
            assertTrue(
                harness.recordingClient.cancelledOrderIds.none { it in stopOrderIds },
                "protective stop orders must not be cancelled when preserveStopLoss=true"
            )
            assertTrue(harness.recordingClient.cancelledOrderIds.isNotEmpty())

            val snapshot = harness.latestOpenOrdersSnapshot()
            assertEquals(stopOrderIds, snapshot.map { it.orderId }.toSet())
            assertTrue(snapshot.all { SessionOrderClassification.isProtectiveStopLoss(it) })
        }
    }

    @Test
    fun closeOpenPosition_explicitSnapshot_placesMarketOrderWithoutPositionCache() = runBlocking {
        withHarness { harness ->
            harness.recordingClient.connected = true
            harness.connection.start()
            harness.connection.nextValidId(700)

            harness.queues.outbound.offer(
                GatewayCommand.CloseOpenPositionForSymbol(
                    symbol = "F",
                    quantity = 301,
                    action = "BUY"
                )
            )
            delay(400)

            val placed = harness.recordingClient.placedOrders.single()
            assertEquals(700, placed.orderId)
            assertTrue(placed.order.action()?.name == "BUY")
            assertTrue(placed.order.getOrderType() == "MKT")
            assertEquals(301L, placed.order.totalQuantity().longValue())
            assertTrue(placed.contract.symbol() == "F")
        }
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queues = BlockingGatewayQueues()
        lateinit var recordingClient: RecordingEClientSocket
        val connection = DesktopIbGatewayConnection(
            queues = queues,
            config = config,
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
        suspend fun latestOpenOrdersSnapshot(): List<daytrader.gateway.WorkingOrder> {
            var latest = emptyList<daytrader.gateway.WorkingOrder>()
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                val event = queues.inbound.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (event is GatewayEvent.OpenOrdersSnapshot) {
                    latest = event.orders
                }
            }
            return latest
        }

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
        val cancelledOrderIds = mutableListOf<Int>()
        @Volatile var connected: Boolean = false

        override fun isConnected(): Boolean = connected

        override fun placeOrder(orderId: Int, contract: Contract, order: Order) {
            placedOrders.add(PlacedOrder(orderId, contract, order))
        }

        override fun cancelOrder(orderId: Int, orderCancel: OrderCancel) {
            cancelledOrderIds.add(orderId)
        }
    }

    private fun submittedOrderState(): OrderState {
        val ctor: Constructor<OrderState> = OrderState::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance().apply {
            status("Submitted")
        }
    }
}
