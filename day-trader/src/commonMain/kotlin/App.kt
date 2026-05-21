import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==========================================
// Color Palette & Enums
// ==========================================
val DarkBackground = Color(0xFF121318)
val SurfaceDark = Color(0xFF1C1D24)
val TableHeaderBg = Color(0xFF252730)
val BrandRed = Color(0xFFD32F2F)
val GainGreen = Color(0xFF00C853)
val LossRed = Color(0xFFFF3D00)
val TextSecondary = Color(0xFF9AA0A6)

enum class SortableColumn {
    SYMBOL, COMPANY, QUANTITY, AVG_PRICE, LAST_PRICE, MARKET_VALUE, DAILY_CHANGE, UNREALIZED_PNL
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

// ==========================================
// Data Models
// ==========================================
data class PositionItem(
    val symbol: String,
    val companyName: String,
    val quantity: Int,
    val avgPrice: Double,
    val marketPrice: Double,
    val dailyChangePct: Double,
    val totalUnrealizedPnL: Double
) {
    val marketValue: Double get() = quantity * marketPrice
    val formattedAvgPrice: String get() = "$${String.format("%.2f", avgPrice)}"
    val formattedMarketPrice: String get() = "$${String.format("%.2f", marketPrice)}"
    val formattedMarketValue: String get() = "$${String.format("%,.2f", marketValue)}"
    val formattedDailyChange: String get() = "${if (dailyChangePct >= 0) "+" else ""}${String.format("%.2f", dailyChangePct)}%"
    val formattedPnL: String get() = "${if (totalUnrealizedPnL >= 0) "+" else ""}$${String.format("%,.2f", totalUnrealizedPnL)}"
}

// ==========================================
// Main Screen Layout
// ==========================================
@Composable
fun App() {
    val rawPositions = remember {
        arrayOf(
            PositionItem("AAPL", "Apple Inc.", 150, 175.20, 181.10, 0.85, 885.00),
            PositionItem("TSLA", "Tesla Inc.", 80, 210.50, 198.30, -2.40, -976.00),
            PositionItem("NVDA", "NVIDIA Corp.", 65, 450.00, 485.25, 3.12, 2291.25),
            PositionItem("MSFT", "Microsoft Corp.", 110, 380.10, 389.50, 0.15, 1034.00),
            PositionItem("AMD", "Advanced Micro Devices", 120, 112.00, 108.40, -1.10, -432.00),
            PositionItem("AMZN", "Amazon.com Inc.", 200, 145.00, 151.20, 1.05, 1240.00)
        )
    }

    var currentSortColumn by remember { mutableStateOf(SortableColumn.SYMBOL) }
    var currentSortDirection by remember { mutableStateOf(SortDirection.ASCENDING) }

    // Dynamic sorting logic handling all columns
    val sortedPositions = remember(rawPositions, currentSortColumn, currentSortDirection) {
        val comparator = when (currentSortColumn) {
            SortableColumn.SYMBOL -> compareBy<PositionItem> { it.symbol }
            SortableColumn.COMPANY -> compareBy { it.companyName }
            SortableColumn.QUANTITY -> compareBy { it.quantity }
            SortableColumn.AVG_PRICE -> compareBy { it.avgPrice }
            SortableColumn.LAST_PRICE -> compareBy { it.marketPrice }
            SortableColumn.MARKET_VALUE -> compareBy { it.marketValue }
            SortableColumn.DAILY_CHANGE -> compareBy { it.dailyChangePct }
            SortableColumn.UNREALIZED_PNL -> compareBy { it.totalUnrealizedPnL }
        }
        if (currentSortDirection == SortDirection.DESCENDING) rawPositions.sortedWith(comparator.reversed())
        else rawPositions.sortedWith(comparator)
    }

    val onHeaderClick: (SortableColumn) -> Unit = { column ->
        if (currentSortColumn == column) {
            currentSortDirection = if (currentSortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
        } else {
            currentSortColumn = column
            currentSortDirection = SortDirection.ASCENDING
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Row(modifier = Modifier.fillMaxSize()) {

                // Navigation Rail
                NavigationRail(
                    containerColor = SurfaceDark,
                    header = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Logo",
                            tint = BrandRed,
                            modifier = Modifier.padding(vertical = 16.dp).size(32.dp)
                        )
                    }
                ) {
                    NavigationRailItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Default.Wallet, "Portfolios") },
                        label = { Text("Positions") },
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = GainGreen, selectedTextColor = Color.White, indicatorColor = Color.Transparent)
                    )
                }

                // Main Content Workspace
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Current Positions", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Account: U1234567 • Fully Sortable Blotter", fontSize = 13.sp, color = TextSecondary)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            var searchBy by remember { mutableStateOf("") }
                            OutlinedTextField(
                                value = searchBy,
                                onValueChange = { searchBy = it },
                                placeholder = { Text("Filter by symbol...", color = TextSecondary) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.width(250.dp).testTag("SearchField")
                            )
                            IconButton(onClick = {}) { Icon(Icons.Default.Notifications, "Notifications", tint = Color.White) }
                            IconButton(onClick = {}) { Icon(Icons.Default.AccountCircle, "Profile", tint = Color.White) }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Open Positions (${sortedPositions.size})", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark), shape = RoundedCornerShape(6.dp)) {
                                Icon(Icons.Default.Download, contentDescription = "Export", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export CSV", color = Color.White, fontSize = 13.sp)
                            }
                            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = BrandRed), shape = RoundedCornerShape(6.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Trade", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Order", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Table Layout Container
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                            .background(SurfaceDark, RoundedCornerShape(8.dp))
                    ) {
                        BlotterHeader(
                            activeSortColumn = currentSortColumn,
                            sortDirection = currentSortDirection,
                            onHeaderClick = onHeaderClick
                        )

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                count = sortedPositions.size,
                                key = { index: Int -> sortedPositions[index].symbol }
                            ) { index: Int ->
                                val position = sortedPositions[index]
                                BlotterRow(position = position)
                                if (index < sortedPositions.size - 1) {
                                    HorizontalDivider(color = DarkBackground, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Table Construction Sub-Components
// ==========================================

@Composable
fun BlotterHeader(
    activeSortColumn: SortableColumn,
    sortDirection: SortDirection,
    onHeaderClick: (SortableColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("BlotterTableHeaderRow"), // Added structurally scoped testTag
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell(
            label = "Symbol",
            columnType = SortableColumn.SYMBOL,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1.2f),
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Company",
            columnType = SortableColumn.COMPANY,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1.8f),
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Position",
            columnType = SortableColumn.QUANTITY,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1f),
            alignEnd = true,
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Avg Price",
            columnType = SortableColumn.AVG_PRICE,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1.2f),
            alignEnd = true,
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Last Price",
            columnType = SortableColumn.LAST_PRICE,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1.2f),
            alignEnd = true,
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Market Value",
            columnType = SortableColumn.MARKET_VALUE,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1.5f),
            alignEnd = true,
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Chg %",
            columnType = SortableColumn.DAILY_CHANGE,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1f),
            alignEnd = true,
            onClick = onHeaderClick
        )

        HeaderCell(
            label = "Unrealized P&L",
            columnType = SortableColumn.UNREALIZED_PNL,
            activeColumn = activeSortColumn,
            direction = sortDirection,
            modifier = Modifier.weight(1.5f),
            alignEnd = true,
            onClick = onHeaderClick
        )
    }
}

@Composable
fun RowScope.HeaderCell(
    label: String,
    columnType: SortableColumn,
    activeColumn: SortableColumn,
    direction: SortDirection,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    onClick: (SortableColumn) -> Unit
) {
    val isActive = activeColumn == columnType
    Row(
        modifier = modifier
            .clickable { onClick(columnType) }
            .padding(vertical = 2.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        )
        if (isActive) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = "Sorted Direction",
                tint = GainGreen,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
fun BlotterRow(position: PositionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("BlotterDataRow"), // Added structurally scoped testTag
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(position.symbol, modifier = Modifier.weight(1.2f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(position.companyName, modifier = Modifier.weight(1.8f), color = TextSecondary, fontSize = 14.sp, maxLines = 1)
        Text(position.quantity.toString(), modifier = Modifier.weight(1f), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.End)
        Text(position.formattedAvgPrice, modifier = Modifier.weight(1.2f), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.End)
        Text(position.formattedMarketPrice, modifier = Modifier.weight(1.2f), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.End)
        Text(position.formattedMarketValue, modifier = Modifier.weight(1.5f), color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, textAlign = TextAlign.End)

        Text(
            text = position.formattedDailyChange,
            modifier = Modifier.weight(1f),
            color = if (position.dailyChangePct >= 0) GainGreen else LossRed,
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
        Text(
            text = position.formattedPnL,
            modifier = Modifier.weight(1.5f),
            color = if (position.totalUnrealizedPnL >= 0) GainGreen else LossRed,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.End
        )
    }
}