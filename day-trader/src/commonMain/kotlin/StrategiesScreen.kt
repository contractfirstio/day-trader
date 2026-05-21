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

private val timeframeOptions = listOf("1m", "5m", "15m")

@Composable
fun StrategiesScreen() {
    val instances = remember { mutableStateListOf(*mockStrategyInstances().toTypedArray()) }
    var selectedInstanceId by remember { mutableStateOf(instances.firstOrNull()?.id) }
    var searchQuery by remember { mutableStateOf("") }
    var instanceFilter by remember { mutableStateOf(InstanceFilter.ALL) }
    var strategyTypeFilter by remember { mutableStateOf<StrategyType?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var detailTab by remember { mutableStateOf(StrategyDetailTab.CONFIGURATION) }

    val selectedInstance = instances.find { it.id == selectedInstanceId }

    val filteredInstances = remember(instances, searchQuery, instanceFilter, strategyTypeFilter) {
        instances.filter { instance ->
            val matchesSearch = searchQuery.isBlank() ||
                instance.name.contains(searchQuery, ignoreCase = true) ||
                instance.symbol.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (instanceFilter) {
                InstanceFilter.ALL -> true
                InstanceFilter.RUNNING -> instance.status == InstanceStatus.RUNNING
                InstanceFilter.STOPPED -> instance.status == InstanceStatus.STOPPED
            }
            val matchesStrategyType = strategyTypeFilter == null || instance.strategyType == strategyTypeFilter
            matchesSearch && matchesFilter && matchesStrategyType
        }
    }

    fun updateInstance(id: String, transform: (StrategyInstance) -> StrategyInstance) {
        val index = instances.indexOfFirst { it.id == id }
        if (index >= 0) instances[index] = transform(instances[index])
    }

    if (showAddDialog) {
        AddStrategyInstanceDialog(
            onDismiss = { showAddDialog = false },
            onCreate = { strategyType, name, symbol, timeframe, riskDollars ->
                val symbolUpper = symbol.uppercase()
                val instance = defaultStrategyInstance(
                    strategyType = strategyType,
                    name = name.ifBlank { defaultInstanceName(strategyType, symbolUpper) },
                    symbol = symbolUpper,
                    timeframe = timeframe,
                    riskDollars = riskDollars
                )
                instances.add(instance)
                selectedInstanceId = instance.id
                detailTab = StrategyDetailTab.CONFIGURATION
                showAddDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).testTag("StrategiesScreen")) {
        StrategiesHeader(
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onAddInstance = { showAddDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        InstanceFilterRow(
            filter = instanceFilter,
            onFilterChange = { instanceFilter = it },
            instanceCount = filteredInstances.size
        )

        Spacer(modifier = Modifier.height(10.dp))

        StrategyTypeFilterRow(
            selectedType = strategyTypeFilter,
            onTypeChange = { strategyTypeFilter = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Left pane: instance list
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
                    "Instances (${filteredInstances.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (filteredInstances.isEmpty()) {
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
                        items(filteredInstances, key = { it.id }) { instance ->
                            StrategyInstanceCard(
                                instance = instance,
                                isSelected = instance.id == selectedInstanceId,
                                onSelect = {
                                    selectedInstanceId = instance.id
                                    detailTab = StrategyDetailTab.CONFIGURATION
                                },
                                onToggleRun = {
                                    val nextStatus = if (instance.status == InstanceStatus.RUNNING) {
                                        InstanceStatus.STOPPED
                                    } else {
                                        InstanceStatus.RUNNING
                                    }
                                    updateInstance(instance.id) { it.copy(status = nextStatus) }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right pane: instance detail
            Column(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
                    .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
                    .testTag("StrategyInstanceDetail")
            ) {
                if (selectedInstance == null) {
                    StrategyDetailEmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    StrategyInstanceDetail(
                        instance = selectedInstance,
                        detailTab = detailTab,
                        onTabChange = { detailTab = it },
                        onUpdate = { transform -> updateInstance(selectedInstance.id, transform) },
                        onStartStop = {
                            val next = if (selectedInstance.status == InstanceStatus.RUNNING) {
                                InstanceStatus.STOPPED
                            } else {
                                InstanceStatus.RUNNING
                            }
                            updateInstance(selectedInstance.id) { it.copy(status = next) }
                        },
                        onDuplicate = {
                            val copy = selectedInstance.copy(
                                id = newStrategyInstanceId(),
                                name = "${selectedInstance.name} (copy)",
                                status = InstanceStatus.STOPPED,
                                todayPnL = 0.0,
                                tradesToday = 0,
                                lastSignal = "—",
                                lastOrder = "—",
                                openPosition = "Flat",
                                lastUpdate = "—"
                            )
                            instances.add(copy)
                            selectedInstanceId = copy.id
                        },
                        onDelete = {
                            instances.removeAll { it.id == selectedInstance.id }
                            selectedInstanceId = instances.firstOrNull()?.id
                        }
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
                label = type.displayName,
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
    instance: StrategyInstance,
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
                Text(instance.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                StrategyTypePill(instance.strategyType.displayName)
            }
            IconButton(onClick = onToggleRun, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (instance.status == InstanceStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (instance.status == InstanceStatus.RUNNING) "Stop" else "Start",
                    tint = if (instance.status == InstanceStatus.RUNNING) LossRed else GainGreen
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(instance.status)
            Text(
                instance.formattedTodayPnL,
                color = if (instance.todayPnL >= 0) GainGreen else LossRed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(instance.paramsSummary, color = TextSecondary, fontSize = 12.sp)
        Text("${instance.tradesToday} trades today", color = TextSecondary, fontSize = 11.sp)
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
    onTabChange: (StrategyDetailTab) -> Unit,
    onUpdate: ((StrategyInstance) -> StrategyInstance) -> Unit,
    onStartStop: () -> Unit,
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
                    Text(instance.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(instance.strategyType.displayName, fontSize = 14.sp, color = BrandRed, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(instance.strategyType.description, fontSize = 12.sp, color = TextSecondary)
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
                StrategyDetailTab.PERFORMANCE -> PerformanceTab(instance)
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
            label = "Instance name",
            value = instance.name,
            onValueChange = { value -> onUpdate { it.copy(name = value) } }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfigField(
                label = "Symbol",
                value = instance.symbol,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onUpdate { it.copy(symbol = value.uppercase()) } }
            )
            ConfigDropdown(
                label = "Timeframe",
                value = instance.timeframe,
                options = timeframeOptions,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onUpdate { it.copy(timeframe = value) } }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfigField(
                label = "Risk per trade (\$)",
                value = instance.riskDollars.toString(),
                modifier = Modifier.weight(1f),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { risk -> onUpdate { it.copy(riskDollars = risk) } }
                }
            )
            ConfigField(
                label = "Position size (shares)",
                value = instance.positionSize.toString(),
                modifier = Modifier.weight(1f),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { size -> onUpdate { it.copy(positionSize = size) } }
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfigField(
                label = "Stop loss (ticks)",
                value = instance.stopLossTicks.toString(),
                modifier = Modifier.weight(1f),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { ticks -> onUpdate { it.copy(stopLossTicks = ticks) } }
                }
            )
            ConfigField(
                label = "Session window",
                value = instance.sessionWindow,
                modifier = Modifier.weight(1f),
                enabled = false,
                onValueChange = {}
            )
        }
        Text(
            "Session window is fixed by the ${instance.strategyType.displayName} strategy.",
            fontSize = 11.sp,
            color = TextSecondary
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
private fun PerformanceTab(instance: StrategyInstance) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PerformanceStatCard(
            label = "Today P&L",
            value = instance.formattedTodayPnL,
            valueColor = if (instance.todayPnL >= 0) GainGreen else LossRed,
            modifier = Modifier.weight(1f)
        )
        PerformanceStatCard(
            label = "Trades today",
            value = instance.tradesToday.toString(),
            modifier = Modifier.weight(1f)
        )
        PerformanceStatCard(
            label = "Status",
            value = instance.status.name.lowercase().replaceFirstChar { it.uppercase() },
            modifier = Modifier.weight(1f)
        )
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
    onCreate: (StrategyType, String, String, String, Int) -> Unit
) {
    var selectedStrategyType by remember { mutableStateOf(StrategyType.TOUCH_AND_TURN_SCALPER) }
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("SPY") }
    var timeframe by remember { mutableStateOf("1m") }
    var riskText by remember { mutableStateOf("500") }

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
                            if (name.isBlank()) {
                                riskText = when (type) {
                                    StrategyType.TOUCH_AND_TURN_SCALPER -> "500"
                                    StrategyType.QUICK_FLIP_SCALPER -> "250"
                                }
                            }
                        }
                    )
                }
                HorizontalDivider(color = TableHeaderBg)
                ConfigField(label = "Instance name (optional)", value = name, onValueChange = { name = it })
                ConfigField(label = "Symbol", value = symbol, onValueChange = { symbol = it })
                ConfigDropdown(
                    label = "Timeframe",
                    value = timeframe,
                    options = timeframeOptions,
                    onValueChange = { timeframe = it }
                )
                ConfigField(label = "Risk per trade (\$)", value = riskText, onValueChange = { riskText = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val defaultRisk = when (selectedStrategyType) {
                        StrategyType.TOUCH_AND_TURN_SCALPER -> 500
                        StrategyType.QUICK_FLIP_SCALPER -> 250
                    }
                    val risk = riskText.toIntOrNull() ?: defaultRisk
                    onCreate(selectedStrategyType, name, symbol, timeframe, risk)
                },
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
                strategyType.displayName,
                color = if (selected) Color.White else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = BrandRed, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(strategyType.description, color = TextSecondary, fontSize = 12.sp)
    }
}
