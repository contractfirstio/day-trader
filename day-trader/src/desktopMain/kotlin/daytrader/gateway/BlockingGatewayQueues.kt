package daytrader.gateway

import java.util.concurrent.LinkedBlockingQueue

class BlockingGatewayQueues {
    val inbound: LinkedBlockingQueue<GatewayEvent> = LinkedBlockingQueue()
    val outbound: LinkedBlockingQueue<GatewayCommand> = LinkedBlockingQueue()
}
