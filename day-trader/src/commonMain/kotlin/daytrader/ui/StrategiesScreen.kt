package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.data.StrategyCatalog
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.presentation.strategies.*
import daytrader.ui.theme.*

@Composable
fun StrategiesScreen(viewModel: StrategiesViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showAddDialog) {
        AddStrategyInstanceDialog(
            onDismiss = viewModel::onDismissAddDialog,
            defaultMaxDollarsFor = viewModel::defaultMaxDollarsFor,
            onCreate = viewModel::onCreateInstance
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).testTag("StrategiesScreen")) {
        StrategiesHeader(
            searchQuery = uiState.searchQuery,
            onSearchChange = viewModel::onSearchChange,
            onAddInstance = viewModel::onShowAddDialog
        )

        Spacer(modifier = Modifier.height(16.dp))

        InstanceFilterRow(
            filter = uiState.instanceFilter,
            onFilterChange = viewModel::onInstanceFilterChange,
            instanceCount = uiState.filteredCount
        )

        Spacer(modifier = Modifier.height(10.dp))

        StrategyTypeFilterRow(
            selectedType = uiState.strategyTypeFilter,
            onTypeChange = viewModel::onStrategyTypeFilterChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .testTag("StrategyInstanceList")
            ) {
                Text(
                    "Instances (${uiState.filteredCount})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.filteredRows.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No instances match your filter.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.filteredRows, key = { it.id }) { row ->
                            StrategyInstanceCard(
                                row = row,
                                isSelected = row.id == uiState.selectedInstanceId,
                                onSelect = { viewModel.onSelectInstance(row.id) },
                                onToggleRun = { viewModel.onToggleRun(row.id) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
                    .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
                    .testTag("StrategyInstanceDetail")
            ) {
                val selected = uiState.selectedInstance
                if (selected == null) {
                    StrategyDetailEmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    StrategyInstanceDetail(
                        instance = selected,
                        detailTab = uiState.detailTab,
                        performance = uiState.performance,
                        onTabChange = viewModel::onDetailTabChange,
                        onUpdate = { transform -> viewModel.onUpdateInstance(selected.id, transform) },
                        onStartStop = { viewModel.onToggleRun(selected.id) },
                        onRunHeaderClick = viewModel::onRunHeaderClick,
                        onDuplicate = viewModel::onDuplicateSelected,
                        onDelete = viewModel::onDeleteSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategiesHeader(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAddInstance: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Strategies", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Run multiple instances of hardcoded strategies",
                fontSize = 13.sp,
                color = TextSecondary
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search instances...", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.width(240.dp).testTag("StrategySearchField")
            )
            Button(
                onClick = onAddInstance,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("AddStrategyInstanceButton")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add instance", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StrategyTypeFilterRow(
    selectedType: StrategyType?,
    onTypeChange: (StrategyType?) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            label = "All strategies",
            selected = selectedType == null,
            onClick = { onTypeChange(null) }
        )
        StrategyType.entries.forEach { type ->
            FilterChip(
                label = StrategyCatalog.displayName(type),
                selected = selectedType == type,
                onClick = { onTypeChange(if (selectedType == type) null else type) }
            )
        }
    }
}

@Composable
private fun InstanceFilterRow(
    filter: InstanceFilter,
    onFilterChange: (InstanceFilter) -> Unit,
    instanceCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = "All",
                selected = filter == InstanceFilter.ALL,
                onClick = { onFilterChange(InstanceFilter.ALL) }
            )
            FilterChip(
                label = "Running",
                selected = filter == InstanceFilter.RUNNING,
                onClick = { onFilterChange(InstanceFilter.RUNNING) }
            )
            FilterChip(
                label = "Stopped",
                selected = filter == InstanceFilter.STOPPED,
                onClick = { onFilterChange(InstanceFilter.STOPPED) }
            )
        }
        Text("$instanceCount shown", fontSize = 12.sp, color = TextSecondary)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) BrandRed.copy(alpha = 0.25f) else DarkBackground
    val borderColor = if (selected) BrandRed else TableHeaderBg
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(bg, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = if (selected) Color.White else TextSecondary,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
    )
}

@Composable
private fun StrategyInstanceCard(
    row: StrategyInstanceRowUi,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleRun: () -> Unit
) {
    val borderColor = if (isSelected) BrandRed else TableHeaderBg
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (isSelected) TableHeaderBg else DarkBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                StrategyTypePill(row.strategyTypeLabel)
            }
            IconButton(onClick = onToggleRun, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (row.status == InstanceStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (row.status == InstanceStatus.RUNNING) "Stop" else "Start",
                    tint = if (row.status == InstanceStatus.RUNNING) LossRed else GainGreen
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(row.status)
            Text(
                row.formattedTodayPnL,
                color = if (row.isPositivePnL) GainGreen else LossRed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(row.paramsSummary, color = TextSecondary, fontSize = 12.sp)
        Text("${row.tradesToday} trades today", color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun StrategyTypePill(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .background(BrandRed.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = BrandRed,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun StatusChip(status: InstanceStatus) {
    val (label, color) = when (status) {
        InstanceStatus.RUNNING -> "Running" to GainGreen
        InstanceStatus.STOPPED -> "Stopped" to TextSecondary
        InstanceStatus.ERROR -> "Error" to LossRed
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(50))
        )
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StrategyDetailEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.TouchApp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("Select an instance or add a strategy", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StrategyInstanceDetail(
    instance: StrategyInstance,
    detailTab: StrategyDetailTab,
    performance: PerformanceUiState?,
    onTabChange: (StrategyDetailTab) -> Unit,
    onUpdate: ((StrategyInstance) -> StrategyInstance) -> Unit,
    onStartStop: () -> Unit,
    onRunHeaderClick: (RunSortColumn) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(StrategyUiMapper.displayName(instance), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        StrategyUiMapper.strategyDisplayName(instance),
                        fontSize = 14.sp,
                        color = BrandRed,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        StrategyUiMapper.strategyDescription(instance),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStartStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (instance.status == InstanceStatus.RUNNING) SurfaceDark else GainGreen
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(
                            if (instance.status == InstanceStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (instance.status == InstanceStatus.RUNNING) "Stop" else "Start")
                    }
                    OutlinedButton(onClick = onDuplicate, shape = RoundedCornerShape(6.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Duplicate")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = LossRed)
                    }
                }
            }
        }

        TabRow(
            selectedTabIndex = detailTab.ordinal,
            containerColor = TableHeaderBg,
            contentColor = Color.White,
            divider = { HorizontalDivider(color = DarkBackground) }
        ) {
            StrategyDetailTab.entries.forEach { tab ->
                Tab(
                    selected = detailTab == tab,
                    onClick = { onTabChange(tab) },
                    text = {
                        Text(
                            when (tab) {
                                StrategyDetailTab.CONFIGURATION -> "Configuration"
                                StrategyDetailTab.ACTIVITY -> "Activity"
                                StrategyDetailTab.PERFORMANCE -> "Performance"
                            },
                            fontSize = 13.sp
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            when (detailTab) {
                StrategyDetailTab.CONFIGURATION -> ConfigurationTab(instance, onUpdate)
                StrategyDetailTab.ACTIVITY -> ActivityTab(instance)
                StrategyDetailTab.PERFORMANCE -> PerformanceTab(
                    performance = performance,
                    onRunHeaderClick = onRunHeaderClick
                )
            }
        }

        HorizontalDivider(color = DarkBackground)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableHeaderBg)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Last update: ${instance.lastUpdate}", fontSize = 11.sp, color = TextSecondary)
            StatusChip(instance.status)
        }
    }
}

@Composable
private fun ConfigurationTab(
    instance: StrategyInstance,
    onUpdate: ((StrategyInstance) -> StrategyInstance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConfigField(
            label = "Symbol",
            value = instance.symbol,
            onValueChange = { value -> onUpdate { it.copy(symbol = value.trim().uppercase()) } }
        )
        ConfigField(
            label = "Max amount (\$)",
            value = instance.maxDollars.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.takeIf { it > 0 }?.let { max ->
                    onUpdate { it.copy(maxDollars = max) }
                }
            }
        )
    }
}

@Composable
private fun ActivityTab(instance: StrategyInstance) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ActivityRow("Last signal", instance.lastSignal)
        ActivityRow("Last order", instance.lastOrder)
        ActivityRow("Open position", instance.openPosition)
    }
}

@Composable
private fun PerformanceTab(
    performance: PerformanceUiState?,
    onRunHeaderClick: (RunSortColumn) -> Unit
) {
    if (performance == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No performance data.", color = TextSecondary, fontSize = 13.sp)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CurrentRunSummaryStrip(performance)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PerformanceStatCard(
                label = "7d P&L",
                value = performance.rollup7d,
                modifier = Modifier.weight(1f)
            )
            PerformanceStatCard(
                label = "30d P&L",
                value = performance.rollup30d,
                modifier = Modifier.weight(1f)
            )
            PerformanceStatCard(
                label = "Win rate",
                value = performance.winRate,
                modifier = Modifier.weight(1f)
            )
        }

        RunBlotterTable(
            performance = performance,
            onHeaderClick = onRunHeaderClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CurrentRunSummaryStrip(performance: PerformanceUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("CurrentRunSummary"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Current run", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    performance.currentRunDateLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (performance.isLive) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Live", fontSize = 11.sp, color = GainGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text("P&L", fontSize = 11.sp, color = TextSecondary)
                Text(
                    performance.currentRunPnL,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (performance.isCurrentRunPositive) GainGreen else LossRed
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Trades", fontSize = 11.sp, color = TextSecondary)
                Text(
                    performance.currentRunTrades.toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Text(label, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun PerformanceStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White
) {
    Column(
        modifier = modifier
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkBackground,
                unfocusedContainerColor = DarkBackground,
                disabledContainerColor = DarkBackground,
                focusedBorderColor = TableHeaderBg,
                unfocusedBorderColor = TableHeaderBg,
                disabledBorderColor = TableHeaderBg,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                disabledTextColor = TextSecondary
            ),
            shape = RoundedCornerShape(6.dp)
        )
    }
}

@Composable
private fun ConfigDropdown(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedBorderColor = TableHeaderBg,
                    unfocusedBorderColor = TableHeaderBg,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddStrategyInstanceDialog(
    onDismiss: () -> Unit,
    defaultMaxDollarsFor: (StrategyType) -> Int,
    onCreate: (StrategyType, String, Int) -> Unit
) {
    var selectedStrategyType by remember { mutableStateOf(StrategyType.TOUCH_AND_TURN_SCALPER) }
    var symbol by remember { mutableStateOf("") }
    var maxDollarsText by remember {
        mutableStateOf(defaultMaxDollarsFor(StrategyType.TOUCH_AND_TURN_SCALPER).toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Add strategy instance", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose strategy", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                StrategyType.entries.forEach { type ->
                    StrategyTypePickerCard(
                        strategyType = type,
                        selected = selectedStrategyType == type,
                        onSelect = {
                            selectedStrategyType = type
                            maxDollarsText = defaultMaxDollarsFor(type).toString()
                        }
                    )
                }
                HorizontalDivider(color = TableHeaderBg)
                ConfigField(label = "Symbol", value = symbol, onValueChange = { symbol = it })
                ConfigField(
                    label = "Max amount (\$)",
                    value = maxDollarsText,
                    onValueChange = { maxDollarsText = it }
                )
            }
        },
        confirmButton = {
            val maxDollars = maxDollarsText.toIntOrNull() ?: 0
            Button(
                onClick = { onCreate(selectedStrategyType, symbol, maxDollars) },
                enabled = symbol.isNotBlank() && maxDollars > 0,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("CreateStrategyInstanceButton")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun StrategyTypePickerCard(
    strategyType: StrategyType,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (selected) BrandRed else TableHeaderBg
    val backgroundColor = if (selected) BrandRed.copy(alpha = 0.12f) else DarkBackground
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("StrategyTypePicker-${strategyType.name}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                StrategyCatalog.displayName(strategyType),
                color = if (selected) Color.White else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = BrandRed, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(StrategyCatalog.description(strategyType), color = TextSecondary, fontSize = 12.sp)
    }
}
