package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import daytrader.domain.BracketAmendTarget
import daytrader.presentation.strategies.TouchTurnBracketAmendUiState
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
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
    val showActionsColumn = uiState.canCancelOrders || uiState.canAmendBrackets
    val amendDialogGroup = uiState.amendDialogSymbolKey?.let { key ->
        uiState.groups.firstOrNull { it.symbolKey == key }
    }
    val amendDialog = amendDialogGroup?.bracketAmend

    amendDialog?.let { amend ->
        TouchTurnBracketAmendDialog(
            amend = amend,
            onDismiss = viewModel::onDismissAmendDialog,
            onApply = { targetQty -> viewModel.onAmendBracket(amend.target, targetQty) },
        )
    }

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
        uiState.cancelMessage?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        uiState.amendFeedbackMessage?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    fontSize = 12.sp,
                    color = GainGreen,
                    modifier = Modifier.weight(1f).testTag("OrdersBracketAmendFeedback"),
                )
                TextButton(
                    onClick = viewModel::onDismissAmendFeedback,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("Dismiss", fontSize = 11.sp, color = TextSecondary)
                }
            }
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
                showActionsColumn = showActionsColumn,
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
                                    showActionsColumn = showActionsColumn,
                                    onClick = { viewModel.onSymbolGroupClick(group.symbolKey) },
                                    onAmendClick = { viewModel.onAmendBracketClick(group.symbolKey) },
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
                                            showActionsColumn = showActionsColumn,
                                            onCancel = { viewModel.onCancelOrder(order.orderId) },
                                            modifier = Modifier.padding(start = 20.dp)
                                        )
                                    }
                                }
                            } else {
                                OrdersTableRow(
                                    row = group.orders.single(),
                                    showActionsColumn = showActionsColumn,
                                    onCancel = { viewModel.onCancelOrder(group.orders.single().orderId) },
                                    bracketAmend = group.bracketAmend,
                                    onAmendClick = { viewModel.onAmendBracketClick(group.symbolKey) },
                                )
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
    showActionsColumn: Boolean,
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
        if (showActionsColumn) {
            Text(
                text = "",
                modifier = Modifier.width(64.dp),
                fontSize = 11.sp
            )
        }
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
    showActionsColumn: Boolean,
    onClick: () -> Unit,
    onAmendClick: () -> Unit,
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
        if (showActionsColumn) {
            OrdersAmendCell(
                bracketAmend = group.bracketAmend,
                onAmendClick = onAmendClick,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

@Composable
private fun OrdersTableRow(
    row: OpenOrderRowUi,
    showActionsColumn: Boolean,
    onCancel: () -> Unit,
    bracketAmend: TouchTurnBracketAmendUiState? = null,
    onAmendClick: (() -> Unit)? = null,
) {
    OrdersDetailRow(
        row = row,
        showActionsColumn = showActionsColumn,
        onCancel = onCancel,
        bracketAmend = bracketAmend,
        onAmendClick = onAmendClick,
    )
}

@Composable
private fun OrdersDetailRow(
    row: OpenOrderRowUi,
    showActionsColumn: Boolean,
    onCancel: () -> Unit,
    bracketAmend: TouchTurnBracketAmendUiState? = null,
    onAmendClick: (() -> Unit)? = null,
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
        if (showActionsColumn) {
            if (bracketAmend != null && onAmendClick != null) {
                OrdersAmendCell(
                    bracketAmend = bracketAmend,
                    onAmendClick = onAmendClick,
                    modifier = Modifier.width(64.dp),
                )
            } else {
                OrdersCancelCell(
                    row = row,
                    onCancel = onCancel,
                    modifier = Modifier.width(64.dp)
                )
            }
        }
    }
}

@Composable
private fun OrdersAmendCell(
    bracketAmend: TouchTurnBracketAmendUiState?,
    onAmendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        when {
            bracketAmend == null -> Unit
            bracketAmend.isApplying -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = TextSecondary
                )
            }
            else -> {
                TextButton(
                    onClick = onAmendClick,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("AmendBracketButton-${bracketAmend.amendKey}")
                ) {
                    Text("Amend", color = GainGreen, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TouchTurnBracketAmendDialog(
    amend: TouchTurnBracketAmendUiState,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var targetText by remember(amend.currentQuantity) {
        mutableStateOf((amend.currentQuantity + 1).toString())
    }
    val parsedTarget = targetText.filter { it.isDigit() }.toIntOrNull()
    val isValid = parsedTarget != null && parsedTarget > amend.currentQuantity

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Amend bracket (test)", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Current qty ${amend.currentQuantity} · entry ${amend.entryPriceLabel} ${amend.currencyCode}",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Text(
                    "Sends an IB placeOrder modify for all bracket legs — no liquidity pool debit.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Target qty") },
                    singleLine = true,
                    enabled = !amend.isApplying,
                    modifier = Modifier.fillMaxWidth().testTag("OrdersBracketAmendTargetQty"),
                    textStyle = TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                amend.error?.let { error ->
                    Text(error, fontSize = 12.sp, color = LossRed, modifier = Modifier.testTag("OrdersBracketAmendError"))
                }
                amend.successMessage?.let { success ->
                    Text(success, fontSize = 12.sp, color = GainGreen, modifier = Modifier.testTag("OrdersBracketAmendSuccess"))
                }
            }
        },
        confirmButton = {
            if (amend.isApplying) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Button(
                    onClick = { onApply(targetText) },
                    enabled = isValid,
                    modifier = Modifier.testTag("OrdersBracketAmendApply"),
                ) {
                    Text("Amend")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !amend.isApplying) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun OrdersCancelCell(
    row: OpenOrderRowUi,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        when {
            row.isCancelling -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = TextSecondary
                )
            }
            row.canCancel -> {
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("CancelOrderButton-${row.orderId}")
                ) {
                    Text("Cancel", color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
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
