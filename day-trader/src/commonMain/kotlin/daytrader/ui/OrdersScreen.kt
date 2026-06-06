package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.presentation.orders.OpenOrderRowUi
import daytrader.presentation.orders.OpenOrderUiMapper
import daytrader.presentation.orders.OrderSortColumn
import daytrader.presentation.orders.OrderSymbolGroupUi
import daytrader.presentation.orders.OrdersViewModel
import daytrader.presentation.positions.SortDirection
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel,
    connectionState: GatewayConnectionState,
    brokerKind: BrokerKind
) {
    val uiState by viewModel.uiState.collectAsState()
    val symbolCount = uiState.groups.size

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            if (symbolCount == uiState.totalOrderCount) {
                "Open orders (${uiState.totalOrderCount})"
            } else {
                "Open orders (${uiState.totalOrderCount} across $symbolCount symbols)"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Text(
            executionSourceLabel(brokerKind),
            fontSize = 12.sp,
            color = TextSecondary
        )
        if (uiState.groups.any { it.isGrouped }) {
            Text(
                "Tap a symbol with multiple orders to expand bracket legs.",
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                .background(SurfaceDark, RoundedCornerShape(8.dp))
        ) {
            OrdersTableHeader(
                activeSortColumn = uiState.sortColumn,
                sortDirection = uiState.sortDirection,
                onHeaderClick = viewModel::onHeaderClick
            )

            if (uiState.groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyOrdersMessage(connectionState, brokerKind),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    uiState.groups.forEachIndexed { groupIndex, group ->
                        item(key = "group-${group.symbolKey}") {
                            if (group.isGrouped) {
                                OrdersGroupSummaryRow(
                                    group = group,
                                    onClick = { viewModel.onSymbolGroupClick(group.symbolKey) }
                                )
                                if (group.isExpanded) {
                                    group.orders.forEachIndexed { orderIndex, order ->
                                        if (orderIndex > 0) {
                                            HorizontalDivider(
                                                color = DarkBackground.copy(alpha = 0.6f),
                                                thickness = 1.dp,
                                                modifier = Modifier.padding(start = 20.dp)
                                            )
                                        }
                                        OrdersDetailRow(
                                            row = order,
                                            modifier = Modifier.padding(start = 20.dp)
                                        )
                                    }
                                }
                            } else {
                                OrdersTableRow(row = group.orders.single())
                            }
                            if (groupIndex < uiState.groups.lastIndex) {
                                HorizontalDivider(color = DarkBackground, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun executionSourceLabel(brokerKind: BrokerKind): String = when (brokerKind) {
    BrokerKind.INTERACTIVE_BROKERS -> "Live orders from Interactive Brokers"
    BrokerKind.EMULATOR -> "Simulated orders from the broker emulator"
    BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper orders (emulator execution · live IB market data)"
    BrokerKind.REPLAY -> "Paper orders during session replay"
}

private fun emptyOrdersMessage(
    connectionState: GatewayConnectionState,
    brokerKind: BrokerKind
): String = when (connectionState) {
    GatewayConnectionState.Connected ->
        "No working orders reported by ${brokerKind.displayName.lowercase()}."
    GatewayConnectionState.Connecting ->
        "Loading open orders…"
    is GatewayConnectionState.Error ->
        "Open orders unavailable — fix broker connection and reconnect."
    GatewayConnectionState.Disconnected ->
        "Connect to your broker to load open orders."
}

@Composable
private fun OrdersTableHeader(
    activeSortColumn: OrderSortColumn,
    sortDirection: SortDirection,
    onHeaderClick: (OrderSortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("OrdersTableHeaderRow"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OrdersHeaderCell("Symbol", OrderSortColumn.SYMBOL, activeSortColumn, sortDirection, Modifier.weight(0.9f), onHeaderClick)
        OrdersHeaderCell("Action", OrderSortColumn.ACTION, activeSortColumn, sortDirection, Modifier.weight(0.7f), onHeaderClick)
        OrdersHeaderCell("Type", OrderSortColumn.TYPE, activeSortColumn, sortDirection, Modifier.weight(0.7f), onHeaderClick)
        OrdersHeaderCell("Leg / Plan", null, activeSortColumn, sortDirection, Modifier.weight(0.9f), onHeaderClick)
        OrdersHeaderCell("Qty", OrderSortColumn.QUANTITY, activeSortColumn, sortDirection, Modifier.weight(0.55f), onHeaderClick)
        OrdersHeaderCell("Price", OrderSortColumn.PRICE, activeSortColumn, sortDirection, Modifier.weight(0.85f), onHeaderClick)
        OrdersHeaderCell("Status", OrderSortColumn.STATUS, activeSortColumn, sortDirection, Modifier.weight(0.9f), onHeaderClick)
        OrdersHeaderCell("Order #", OrderSortColumn.ORDER_ID, activeSortColumn, sortDirection, Modifier.weight(0.65f), onHeaderClick)
    }
}

@Composable
private fun RowScope.OrdersHeaderCell(
    label: String,
    column: OrderSortColumn?,
    activeColumn: OrderSortColumn,
    direction: SortDirection,
    modifier: Modifier = Modifier,
    onClick: (OrderSortColumn) -> Unit
) {
    val isActive = column != null && activeColumn == column
    Row(
        modifier = modifier
            .then(if (column != null) Modifier.clickable { onClick(column) } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isActive) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = if (direction == SortDirection.ASCENDING) {
                    Icons.Default.ArrowUpward
                } else {
                    Icons.Default.ArrowDownward
                },
                contentDescription = "Sorted direction",
                tint = GainGreen,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
private fun OrdersGroupSummaryRow(
    group: OrderSymbolGroupUi,
    onClick: () -> Unit
) {
    val summary = OpenOrderUiMapper.collapsedSummary(group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (group.isExpanded) DarkBackground.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("OrdersGroupRow-${group.symbolKey}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(0.9f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                group.displaySymbol,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1
            )
            Icon(
                imageVector = if (group.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (group.isExpanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(summary.actionLabel, Modifier.weight(0.7f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(summary.typeLabel, Modifier.weight(0.7f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        OrderLegPlanCell(
            legLabel = summary.legLabel,
            planLabel = group.orders.firstNotNullOfOrNull { it.sourcePlanLabel },
            modifier = Modifier.weight(0.9f)
        )
        Text(summary.quantityLabel, Modifier.weight(0.55f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(summary.priceLabel, Modifier.weight(0.85f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(summary.statusLabel, Modifier.weight(0.9f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(
            summary.orderCountLabel,
            Modifier.weight(0.65f),
            color = GainGreen,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun OrdersTableRow(row: OpenOrderRowUi) {
    OrdersDetailRow(row = row)
}

@Composable
private fun OrdersDetailRow(
    row: OpenOrderRowUi,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("OrdersTableRow-${row.orderId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.symbol, Modifier.weight(0.9f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        Text(row.action, Modifier.weight(0.7f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(row.orderType, Modifier.weight(0.7f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        OrderLegPlanCell(
            legLabel = row.legLabel,
            planLabel = row.sourcePlanLabel,
            modifier = Modifier.weight(0.9f)
        )
        Text(row.quantityLabel, Modifier.weight(0.55f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(row.priceLabel.ifBlank { "—" }, Modifier.weight(0.85f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(row.status, Modifier.weight(0.9f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(row.orderId.toString(), Modifier.weight(0.65f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun OrderLegPlanCell(
    legLabel: String,
    planLabel: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(legLabel, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        planLabel?.let {
            Text(it, color = GainGreen, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
