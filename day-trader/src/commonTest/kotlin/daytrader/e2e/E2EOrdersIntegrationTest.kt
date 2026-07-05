package daytrader.e2e

import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.e2e.support.E2ESyncedOpenOrderRepository
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.WorkingOrder
import daytrader.presentation.orders.OpenOrderUiMapper
import daytrader.presentation.orders.OrderSortColumn
import daytrader.presentation.orders.OrdersViewModel
import daytrader.presentation.positions.SortDirection
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
 * End-to-end: gateway open orders → synced repository → [OrdersViewModel] UI groups.
 */
class E2EOrdersIntegrationTest {
    @E2EEmulatorTest
    @Test
    fun gatewayOpenOrders_syncsToOrdersViewModelBracketGroups() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val openOrderRepository = E2ESyncedOpenOrderRepository(gateway, scope)
            gateway.setOpenOrders(bracketOrders(symbol = "AAPL"))

            val viewModel = OrdersViewModel(
                repository = openOrderRepository,
                brokerKind = BrokerKind.EMULATOR,
            )
            delay(50)

            val state = viewModel.uiState.value
            assertEquals(1, state.groups.size)
            assertEquals(3, state.totalOrderCount)
            val group = state.groups.single()
            assertEquals("AAPL", group.displaySymbol)
            assertTrue(group.isGrouped)
            assertEquals(3, group.orders.size)
            assertEquals("Bracket", OpenOrderUiMapper.collapsedSummary(group).typeLabel)
        } finally {
            scope.cancel()
        }
    }

    @E2EEmulatorTest
    @Test
    fun viewModel_symbolGroupClick_expandsBracketLegs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val openOrderRepository = E2ESyncedOpenOrderRepository(gateway, scope)
            gateway.setOpenOrders(bracketOrders(symbol = "AAPL"))

            val viewModel = OrdersViewModel(repository = openOrderRepository)
            delay(50)

            val group = viewModel.uiState.value.groups.single()
            assertEquals(false, group.isExpanded)
            viewModel.onSymbolGroupClick(group.symbolKey)
            delay(25)
            assertEquals(true, viewModel.uiState.value.groups.single().isExpanded)
        } finally {
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun viewModel_watchlistLinkedOrders_enrichesPlanLabels() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            val openOrderRepository = E2ESyncedOpenOrderRepository(gateway, scope)
            gateway.setOpenOrders(
                listOf(
                    workingOrder(orderId = 42, symbol = "AAPL", parentOrderId = 0),
                )
            )
            val watchlistRepo = InMemoryWatchlistRepository(
                listOf(
                    defaultWatchlist().copy(
                        entries = listOf(
                            newWatchlistEntry(
                                symbol = "AAPL",
                                marketZoneId = "America/New_York",
                                currencyCode = "USD",
                                companyName = "Apple",
                                instrument = null,
                            ).copy(
                                tradePlans = listOf(
                                    WatchlistTradePlan(
                                        id = "plan-morning",
                                        label = "Morning scalp",
                                        orderPlacedAtEpochMs = 1L,
                                        placedOrderIds = listOf(42),
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val viewModel = OrdersViewModel(
                repository = openOrderRepository,
                watchlistRepository = watchlistRepo,
                brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            )
            delay(50)

            val row = viewModel.uiState.value.groups.single().orders.single()
            assertEquals("AAPL · Morning scalp", row.sourcePlanLabel)
            assertEquals("Interactive Brokers", viewModel.uiState.value.brokerLabel)
        } finally {
            scope.cancel()
        }
    }

    @E2EEmulatorTest
    @Test
    fun viewModel_headerClick_togglesSortDirection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val openOrderRepository = E2ESyncedOpenOrderRepository(gateway, scope)
            gateway.setOpenOrders(
                listOf(
                    workingOrder(orderId = 2, symbol = "MSFT"),
                    workingOrder(orderId = 1, symbol = "AAPL"),
                )
            )

            val viewModel = OrdersViewModel(repository = openOrderRepository)
            delay(50)
            assertEquals(SortDirection.ASCENDING, viewModel.uiState.value.sortDirection)

            viewModel.onHeaderClick(OrderSortColumn.SYMBOL)
            delay(25)
            assertEquals(SortDirection.DESCENDING, viewModel.uiState.value.sortDirection)
            assertEquals(listOf("MSFT", "AAPL"), viewModel.uiState.value.groups.map { it.displaySymbol })
        } finally {
            scope.cancel()
        }
    }

    private fun bracketOrders(symbol: String): List<WorkingOrder> {
        val entryId = 100
        return listOf(
            workingOrder(orderId = entryId, symbol = symbol, parentOrderId = 0, orderType = "LMT"),
            workingOrder(orderId = 101, symbol = symbol, parentOrderId = entryId, orderType = "LMT"),
            workingOrder(orderId = 102, symbol = symbol, parentOrderId = entryId, orderType = "STP"),
        )
    }

    private fun workingOrder(
        orderId: Int,
        symbol: String,
        parentOrderId: Int = 0,
        orderType: String = "LMT",
    ) = WorkingOrder(
        orderId = orderId,
        parentOrderId = parentOrderId,
        symbol = symbol,
        action = "BUY",
        quantity = 10,
        filled = 0,
        remaining = 10,
        orderType = orderType,
        limitPrice = if (orderType == "LMT") 100.0 else null,
        stopPrice = if (orderType == "STP") 95.0 else null,
        status = "Submitted",
        currency = "USD",
    )
}
