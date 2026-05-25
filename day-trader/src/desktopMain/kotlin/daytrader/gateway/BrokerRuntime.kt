package daytrader.gateway

import daytrader.broker.DesktopIbGatewayConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

data class BrokerRuntime(
    val queues: BlockingGatewayQueues,
    val adapter: DesktopIbGatewayConnection,
    val gateway: QueuedBrokerGateway
) {
    fun start() {
        adapter.start()
        gateway.connect()
    }

    fun shutdown() {
        adapter.shutdown()
    }

    companion object {
        fun create(
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): BrokerRuntime {
            val queues = BlockingGatewayQueues()
            val adapter = DesktopIbGatewayConnection(queues = queues, scope = scope)
            val gateway = QueuedBrokerGateway(
                sendCommand = { queues.outbound.offer(it) },
                receiveEventBlocking = { queues.inbound.take() },
                scope = scope
            )
            return BrokerRuntime(queues, adapter, gateway)
        }
    }
}
