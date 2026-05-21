import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .testTag("BlotterTableHeaderRow"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell("Symbol", SortableColumn.SYMBOL, activeSortColumn, sortDirection, Modifier.weight(1.2f), onClick = onHeaderClick)
        HeaderCell("Company", SortableColumn.COMPANY, activeSortColumn, sortDirection, Modifier.weight(1.8f), onClick = onHeaderClick)
        HeaderCell("Position", SortableColumn.QUANTITY, activeSortColumn, sortDirection, Modifier.weight(1f), alignEnd = true, onClick = onHeaderClick)
        HeaderCell("Avg Price", SortableColumn.AVG_PRICE, activeSortColumn, sortDirection, Modifier.weight(1.2f), alignEnd = true, onClick = onHeaderClick)
        HeaderCell("Last Price", SortableColumn.LAST_PRICE, activeSortColumn, sortDirection, Modifier.weight(1.2f), alignEnd = true, onClick = onHeaderClick)
        HeaderCell("Market Value", SortableColumn.MARKET_VALUE, activeSortColumn, sortDirection, Modifier.weight(1.5f), alignEnd = true, onClick = onHeaderClick)
        HeaderCell("Chg %", SortableColumn.DAILY_CHANGE, activeSortColumn, sortDirection, Modifier.weight(1f), alignEnd = true, onClick = onHeaderClick)
        HeaderCell("Unrealized P&L", SortableColumn.UNREALIZED_PNL, activeSortColumn, sortDirection, Modifier.weight(1.5f), alignEnd = true, onClick = onHeaderClick)
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
            .testTag("BlotterDataRow"),
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
