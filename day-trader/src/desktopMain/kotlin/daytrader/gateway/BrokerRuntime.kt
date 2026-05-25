package daytrader.gateway

import daytrader.broker.DesktopIbGatewayConnection
import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

data class BrokerRuntime(
    val kind: BrokerKind,
    val queues: BlockingGatewayQueues,
    val adapter: BrokerAdapter,
    val gateway: QueuedBrokerGateway
) {
    fun start() {
        adapter.start()
        gateway.connect()
    }

    fun shutdown() {
        queues.outbound.offer(GatewayCommand.Shutdown)
        adapter.shutdown()
    }

    companion object {
        fun create(
            kind: BrokerKind = BrokerKind.fromEnvironment(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): BrokerRuntime {
            val queues = BlockingGatewayQueues()
            val brokerId = BrokerId.from(kind)
            val adapter: BrokerAdapter = when (kind) {
                BrokerKind.INTERACTIVE_BROKERS -> DesktopIbGatewayConnection(queues = queues, scope = scope)
                BrokerKind.EMULATOR -> EmulatorBrokerAdapter(
                    emit = { queues.inbound.offer(it) },
                    receiveCommand = { queues.outbound.take() },
                    config = BrokerEmulatorConfig.fromEnvironment(),
                    scope = scope
                )
            }
            val gateway = QueuedBrokerGateway(
                sendCommand = { queues.outbound.offer(it) },
                receiveEventBlocking = { queues.inbound.take() },
                brokerId = brokerId,
                scope = scope
            )
            return BrokerRuntime(kind, queues, adapter, gateway)
        }
    }
}
