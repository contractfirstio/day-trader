import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PositionsScreen() {
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
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
