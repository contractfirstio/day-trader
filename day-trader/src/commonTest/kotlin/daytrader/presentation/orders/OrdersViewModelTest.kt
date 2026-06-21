package daytrader.presentation.orders

import daytrader.data.OpenOrderRepository
import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.BrokerId
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

class OpenOrderUiMapperTest {
    @Test
    fun buildSymbolGroups_groupsByNormalizedSymbol() {
        val rows = listOf(
            row(orderId = 1, symbol = "AAPL"),
            row(orderId = 2, symbol = "AAPL"),
            row(orderId = 3, symbol = "MSFT")
        )
        val groups = OpenOrderUiMapper.buildSymbolGroups(rows, expandedSymbolKey = "AAPL")
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.symbolKey == "AAPL" }.orders.size)
        assertTrue(groups.first { it.symbolKey == "AAPL" }.isExpanded)
    }

    @Test
    fun collapsedSummary_describesBracketGroup() {
        val group = OrderSymbolGroupUi(
            symbolKey = "AAPL",
            displaySymbol = "AAPL",
            orders = listOf(
                row(orderId = 100, symbol = "AAPL", parentOrderId = 0, legLabel = "Entry"),
                row(orderId = 101, symbol = "AAPL", parentOrderId = 100, legLabel = "Target"),
                row(orderId = 102, symbol = "AAPL", parentOrderId = 100, legLabel = "Stop", orderType = "STP")
            ),
            isExpanded = false
        )
        val summary = OpenOrderUiMapper.collapsedSummary(group)
        assertEquals("3 orders", summary.orderCountLabel)
        assertEquals("Bracket", summary.typeLabel)
        assertEquals("3 legs", summary.legLabel)
    }

    @Test
    fun workingOrders_excludesFilledAndCancelled() {
        val orders = listOf(
            sampleOrder(orderId = 1, status = "Submitted", remaining = 10),
            sampleOrder(orderId = 2, status = "Filled", remaining = 0),
            sampleOrder(orderId = 3, status = "Cancelled", remaining = 5)
        )
        assertEquals(1, OpenOrderUiMapper.workingOrders(orders).size)
        assertEquals(1, OpenOrderUiMapper.workingOrders(orders).single().orderId)
    }

    @Test
    fun toRowUi_labelsBracketLegs() {
        val entry = OpenOrderUiMapper.toRowUi(
            sampleOrder(orderId = 100, parentOrderId = 0, orderType = "LMT", remaining = 10)
        )
        val target = OpenOrderUiMapper.toRowUi(
            sampleOrder(orderId = 101, parentOrderId = 100, orderType = "LMT", remaining = 10)
        )
        val stop = OpenOrderUiMapper.toRowUi(
            sampleOrder(orderId = 102, parentOrderId = 100, orderType = "STP", remaining = 10)
        )
        assertEquals("Entry", entry.legLabel)
        assertEquals("Target", target.legLabel)
        assertEquals("Stop", stop.legLabel)
    }

    private fun row(
        orderId: Int,
        symbol: String,
        parentOrderId: Int = 0,
        orderType: String = "LMT",
        legLabel: String = "Entry"
    ) = OpenOrderRowUi(
        orderId = orderId,
        parentOrderId = parentOrderId,
        symbol = symbol,
        action = "BUY",
        orderType = orderType,
        quantityLabel = "10",
        priceLabel = "$100.00",
        status = "Submitted",
        legLabel = legLabel
    )

    private fun sampleOrder(
        orderId: Int,
        parentOrderId: Int = 0,
        orderType: String = "LMT",
        status: String = "Submitted",
        remaining: Int = 10
    ) = WorkingOrder(
        orderId = orderId,
        parentOrderId = parentOrderId,
        symbol = "AAPL",
        action = "BUY",
        quantity = 10,
        filled = 10 - remaining,
        remaining = remaining,
        orderType = orderType,
        limitPrice = 100.0,
        stopPrice = null,
        status = status,
        currency = "USD"
    )
}

class OrdersViewModelTest {
    @Test
    fun groupsOrdersBySymbol() = runBlocking {
        val repository = FakeOpenOrderRepository(
            listOf(
                sampleOrder(orderId = 2, symbol = "MSFT"),
                sampleOrder(orderId = 1, symbol = "AAPL"),
                sampleOrder(orderId = 3, symbol = "AAPL", parentOrderId = 1, orderType = "STP")
            )
        )
        val viewModel = OrdersViewModel(repository = repository)
        delay(50)
        assertEquals(2, viewModel.uiState.value.groups.size)
        assertEquals(3, viewModel.uiState.value.totalOrderCount)
        val aapl = viewModel.uiState.value.groups.first { it.displaySymbol == "AAPL" }
        assertTrue(aapl.isGrouped)
        assertEquals(2, aapl.orders.size)
    }

    @Test
    fun togglesExpandedSymbolGroup() = runBlocking {
        val repository = FakeOpenOrderRepository(
            listOf(
                sampleOrder(orderId = 1, symbol = "AAPL"),
                sampleOrder(orderId = 2, symbol = "AAPL", parentOrderId = 1)
            )
        )
        val viewModel = OrdersViewModel(repository = repository)
        delay(50)
        val group = viewModel.uiState.value.groups.single()
        assertEquals(false, group.isExpanded)
        viewModel.onSymbolGroupClick(group.symbolKey)
        delay(25)
        assertEquals(true, viewModel.uiState.value.groups.single().isExpanded)
    }

    @Test
    fun sortsSymbolGroupsBySymbol() = runBlocking {
        val repository = FakeOpenOrderRepository(
            listOf(
                sampleOrder(orderId = 2, symbol = "MSFT"),
                sampleOrder(orderId = 1, symbol = "AAPL")
            )
        )
        val viewModel = OrdersViewModel(repository = repository)
        delay(50)
        assertEquals(listOf("AAPL", "MSFT"), viewModel.uiState.value.groups.map { it.displaySymbol })
    }

    @Test
    fun cancelOrder_requestsGatewayCancelWhenConnected() = runBlocking {
        val order = sampleOrder(orderId = 42, symbol = "AAPL")
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        gateway.setOpenOrders(listOf(order))
        val repository = FakeOpenOrderRepository(listOf(order))
        val viewModel = OrdersViewModel(
            repository = repository,
            executionGateway = gateway
        )
        delay(50)
        assertTrue(viewModel.uiState.value.canCancelOrders)

        viewModel.onCancelOrder(42)
        delay(50)

        assertEquals(listOf(42), gateway.cancelledOrderIds)
    }

    @Test
    fun cancelOrder_whenDisconnected_showsMessage() = runBlocking {
        val order = sampleOrder(orderId = 42, symbol = "AAPL")
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        gateway.disconnect()
        val repository = FakeOpenOrderRepository(listOf(order))
        val viewModel = OrdersViewModel(
            repository = repository,
            executionGateway = gateway
        )
        delay(50)
        assertEquals(false, viewModel.uiState.value.canCancelOrders)

        viewModel.onCancelOrder(42)
        delay(25)

        assertEquals(
            "Connect to your broker to cancel orders.",
            viewModel.uiState.value.cancelMessage
        )
        assertTrue(gateway.cancelledOrderIds.isEmpty())
    }

    @Test
    fun toRowUi_trailAdjustmentOrdersAreNotCancellable() {
        val row = OpenOrderUiMapper.toRowUi(
            sampleOrder(orderId = 99, symbol = "AAPL").copy(isTrailAdjustment = true)
        )
        assertEquals(false, row.canCancel)
    }

    private fun sampleOrder(orderId: Int, symbol: String, parentOrderId: Int = 0, orderType: String = "LMT") = WorkingOrder(
        orderId = orderId,
        parentOrderId = parentOrderId,
        symbol = symbol,
        action = "BUY",
        quantity = 5,
        filled = 0,
        remaining = 5,
        orderType = orderType,
        limitPrice = 50.0,
        stopPrice = null,
        status = "Submitted",
        currency = "USD"
    )
}

private class FakeOpenOrderRepository(
    orders: List<WorkingOrder>
) : OpenOrderRepository {
    private val _openOrders = MutableStateFlow(orders)
    override val openOrders: StateFlow<List<WorkingOrder>> = _openOrders.asStateFlow()
}
