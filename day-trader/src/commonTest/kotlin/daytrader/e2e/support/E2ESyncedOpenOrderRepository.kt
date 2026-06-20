package daytrader.e2e.support

import daytrader.data.OpenOrderRepository
import daytrader.gateway.BrokerGateway
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Mirrors [daytrader.data.GatewayOpenOrderRepository] for commonTest E2E wiring. */
class E2ESyncedOpenOrderRepository(
    gateway: BrokerGateway,
    scope: CoroutineScope,
) : OpenOrderRepository {
    private val _openOrders = MutableStateFlow(gateway.openOrders.value)
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()

    init {
        gateway.openOrders
            .onEach { orders -> _openOrders.value = orders }
            .launchIn(scope)
    }
}
