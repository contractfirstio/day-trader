package daytrader.gateway

import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnTradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue

/**
 * Tier 2: [QueuedBrokerGateway] inbound consumer — bracket ack delivery when processing is paused/resumed.
 */
class QueuedBrokerGatewayTest {
    @Test
    fun pauseInboundProcessing_delaysTouchTurnBracketAckUntilResumed() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbound = LinkedBlockingQueue<GatewayEvent>()
        try {
            val gateway = QueuedBrokerGateway(
                sendCommand = { },
                receiveEventBlocking = { inbound.take() },
                brokerId = BrokerId.INTERACTIVE_BROKERS,
                scope = scope,
                initialPauseInboundProcessing = true,
            )
            val received = mutableListOf<TouchTurnBracketAck>()
            gateway.touchTurnBracketPlacements
                .onEach { received += it }
                .launchIn(scope)

            val plan = samplePlan()
            val ack = TouchTurnBracketAck(
                symbol = plan.symbol,
                orderIds = listOf(100, 101, 102),
                result = Result.success(Unit),
                plan = plan,
            )

            inbound.offer(GatewayEvent.TouchTurnBracketPlaced(ack))
            delay(150)
            assertTrue(received.isEmpty(), "paused consumer must not deliver bracket ack")

            gateway.setPauseInboundProcessing(false)
            delay(150)
            assertEquals(1, received.size)
            Unit

            gateway.shutdownInboundConsumer()
            inbound.offer(GatewayEvent.InboundShutdown)
        } finally {
            scope.cancel()
        }
        }
    }

    @Test
    fun connectionStateSnapshot_appliedFromInboundQueue() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbound = LinkedBlockingQueue<GatewayEvent>()
        try {
            val gateway = QueuedBrokerGateway(
                sendCommand = { },
                receiveEventBlocking = { inbound.take() },
                brokerId = BrokerId.EMULATOR,
                scope = scope,
            )
            assertEquals(GatewayConnectionState.Disconnected, gateway.connectionState.value)

            inbound.offer(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Connected))
            delay(50)
            assertEquals(GatewayConnectionState.Connected, gateway.connectionState.value)

            inbound.offer(GatewayEvent.ConnectionStateChanged(GatewayConnectionState.Disconnected))
            delay(50)
            assertEquals(GatewayConnectionState.Disconnected, gateway.connectionState.value)

            gateway.shutdownInboundConsumer()
            inbound.offer(GatewayEvent.InboundShutdown)
        } finally {
            scope.cancel()
        }
        }
    }

    private fun samplePlan(): TouchTurnOrderPlan = TouchTurnOrderPlan(
        symbol = "AAPL",
        currencyCode = "USD",
        side = TouchTurnTradeSide.LONG,
        quantity = 5,
        orders = listOf(
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.ENTRY,
                action = "BUY",
                orderType = "LMT",
                price = 100.0,
                quantity = 5,
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.TAKE_PROFIT,
                action = "SELL",
                orderType = "LMT",
                price = 101.0,
                quantity = 5,
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.STOP_LOSS,
                action = "SELL",
                orderType = "STP",
                price = 99.0,
                quantity = 5,
            ),
        ),
    )
}
