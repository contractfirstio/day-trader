package daytrader.data

import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.flow.StateFlow

interface OpenOrderRepository {
    val openOrders: StateFlow<List<WorkingOrder>>
}
