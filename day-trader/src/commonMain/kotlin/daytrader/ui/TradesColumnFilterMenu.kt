package daytrader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.trades.TradeFilterColumn
import daytrader.presentation.trades.TradeSetFilterUi
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
internal fun TradesFilterableHeaderCell(
    label: String,
    filter: TradeSetFilterUi?,
    expanded: Boolean,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    onFilterDismiss: () -> Unit,
    onFilterSelectAll: (Boolean) -> Unit,
    onFilterOptionToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    sortActive: Boolean = false,
    sortAscending: Boolean = false,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onSortClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = if (sortActive) Color.White else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                if (sortActive) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = "Sorted direction",
                        tint = GainGreen,
                        modifier = Modifier
                            .width(12.dp)
                            .heightIn(max = 12.dp)
                    )
                }
            }
            if (filter != null) {
                IconButton(
                    onClick = onFilterClick,
                    modifier = Modifier
                        .width(24.dp)
                        .testTag("TradesColumnFilterButton-${filter.column.name}")
                ) {
                    Icon(
                        imageVector = if (filter.isActive) Icons.Default.FilterAlt else Icons.Default.ArrowDropDown,
                        contentDescription = "Filter $label",
                        tint = if (filter.isActive) GainGreen else TextSecondary,
                        modifier = Modifier
                            .width(16.dp)
                            .heightIn(max = 16.dp)
                    )
                }
            }
        }
        if (filter != null) {
            TradesColumnFilterMenu(
                filter = filter,
                expanded = expanded,
                onDismiss = onFilterDismiss,
                onSelectAllChanged = onFilterSelectAll,
                onOptionToggled = onFilterOptionToggled,
            )
        }
    }
}

@Composable
internal fun TradesColumnFilterMenu(
    filter: TradeSetFilterUi,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelectAllChanged: (Boolean) -> Unit,
    onOptionToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
            .width(200.dp)
            .testTag("TradesColumnFilterMenu-${filter.column.name}"),
        shape = RoundedCornerShape(8.dp),
        containerColor = SurfaceDark,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, TableHeaderBg),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectAllChanged(!filter.allSelected) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("TradesColumnFilterSelectAll-${filter.column.name}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = filter.allSelected,
                    onCheckedChange = onSelectAllChanged,
                    colors = CheckboxDefaults.colors(
                        checkedColor = BrandRed,
                        checkmarkColor = Color.White,
                        uncheckedColor = TextSecondary,
                    )
                )
                Text("Select all", fontSize = 12.sp, color = Color.White)
            }
            HorizontalDivider(color = TableHeaderBg)
            filter.options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOptionToggled(option.value) }
                        .padding(horizontal = 8.dp, vertical = 0.dp)
                        .testTag("TradesColumnFilterOption-${filter.column.name}-${option.value}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = option.selected,
                        onCheckedChange = { onOptionToggled(option.value) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = BrandRed,
                            checkmarkColor = Color.White,
                            uncheckedColor = TextSecondary,
                        )
                    )
                    Text(option.label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.92f))
                }
            }
        }
    }
}
