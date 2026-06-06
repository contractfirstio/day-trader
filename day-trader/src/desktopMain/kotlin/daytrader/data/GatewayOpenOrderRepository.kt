package daytrader.data

import daytrader.gateway.BrokerGateway
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class GatewayOpenOrderRepository(
    gateway: BrokerGateway,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : OpenOrderRepository {

    private val _openOrders = MutableStateFlow<List<WorkingOrder>>(emptyList())
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()

    init {
        gateway.openOrders
            .onEach { orders -> _openOrders.value = orders }
            .launchIn(scope)
    }
}
