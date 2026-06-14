package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import daytrader.data.StrategyCatalog
import daytrader.domain.StrategyType
import daytrader.presentation.strategies.DeploymentImportPhase
import daytrader.presentation.strategies.DeploymentImportRowStatus
import daytrader.presentation.strategies.DeploymentImportRowUi
import daytrader.presentation.strategies.DeploymentSymbolImportUiState
import daytrader.presentation.strategies.SymbolImportTarget
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
internal fun DeploymentSymbolImportDialog(
    state: DeploymentSymbolImportUiState,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onImportTargetChange: (SymbolImportTarget) -> Unit,
    onStrategyTypeChange: (StrategyType) -> Unit,
    onMaxDollarsChange: (String) -> Unit,
    onStartImport: () -> Unit
) {
    Dialog(onDismissRequest = { if (state.canDismiss) onDismiss() }) {
        Surface(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 560.dp)
                .testTag("DeploymentSymbolImportDialog"),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Import symbols from CSV",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "CSV format: symbol,exchange — e.g. META,US or NWG,UK or 1211,HK. " +
                        "Each row is verified via Interactive Brokers before import.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                if (!state.brokerConnected && state.phase == DeploymentImportPhase.CONFIG) {
                    Text(
                        "IB gateway is not connected — imports will use estimated market data only.",
                        color = BrandRed.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
                when (state.phase) {
                    DeploymentImportPhase.CONFIG -> ImportConfigSection(
                        state = state,
                        onPickFile = onPickFile,
                        onImportTargetChange = onImportTargetChange,
                        onStrategyTypeChange = onStrategyTypeChange,
                        onMaxDollarsChange = onMaxDollarsChange
                    )
                    DeploymentImportPhase.IMPORTING, DeploymentImportPhase.COMPLETE -> ImportProgressSection(state)
                }
                ImportActionRow(
                    state = state,
                    onDismiss = onDismiss,
                    onStartImport = onStartImport
                )
            }
        }
    }
}

@Composable
private fun ImportConfigSection(
    state: DeploymentSymbolImportUiState,
    onPickFile: () -> Unit,
    onImportTargetChange: (SymbolImportTarget) -> Unit,
    onStrategyTypeChange: (StrategyType) -> Unit,
    onMaxDollarsChange: (String) -> Unit
) {
    OutlinedButton(onClick = onPickFile, modifier = Modifier.testTag("ImportCsvPickFileButton")) {
        Text("Choose CSV file", color = Color.White)
    }
    state.filePath?.let { path ->
        Text(path, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Text("Import as", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            label = "Deployments",
            selected = state.target == SymbolImportTarget.DEPLOYMENT,
            onClick = { onImportTargetChange(SymbolImportTarget.DEPLOYMENT) }
        )
        FilterChip(
            label = "Watchlist",
            selected = state.target == SymbolImportTarget.WATCHLIST,
            onClick = {
                if (state.watchlistImportEnabled) {
                    onImportTargetChange(SymbolImportTarget.WATCHLIST)
                }
            }
        )
    }
    if (state.target == SymbolImportTarget.DEPLOYMENT) {
        Text("Strategy", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrategyType.values().forEach { type ->
                val selected = state.strategyType == type
                FilterChip(
                    label = StrategyCatalog.displayName(type),
                    selected = selected,
                    onClick = { onStrategyTypeChange(type) }
                )
            }
        }
        OutlinedTextField(
            value = state.maxDollarsText,
            onValueChange = onMaxDollarsChange,
            label = { Text("Max dollars per deployment", color = TextSecondary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = TextSecondary,
                unfocusedBorderColor = TableHeaderBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (state.parseErrors.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(BrandRed.copy(alpha = 0.12f))
                .padding(10.dp)
        ) {
            Text("Parse errors", color = BrandRed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            state.parseErrors.forEach { error ->
                Text(
                    "Line ${error.lineNumber}: ${error.message}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    if (state.rows.isNotEmpty()) {
        val skippedExisting = state.rows.count { it.status == DeploymentImportRowStatus.SKIPPED }
        Text(
            when {
                skippedExisting > 0 && state.pendingImportCount > 0 ->
                    "${state.pendingImportCount} symbol(s) ready · $skippedExisting ${state.existingSkipLabel} (skipped)"
                skippedExisting > 0 ->
                    "All ${state.rows.size} symbol(s) ${state.existingSkipLabel}"
                else ->
                    "${state.rows.size} symbol(s) ready to import"
            },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        ImportRowPreviewList(rows = state.rows, showStatus = state.parseErrors.isEmpty())
    }
}

@Composable
private fun ImportProgressSection(state: DeploymentSymbolImportUiState) {
    if (state.total > 0) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = GainGreen,
            trackColor = DarkBackground,
            progress = { state.completed.toFloat() / state.total.toFloat() }
        )
        Text(
            when (state.phase) {
                DeploymentImportPhase.IMPORTING ->
                    "Importing ${state.completed}/${state.total}…"
                DeploymentImportPhase.COMPLETE ->
                    "Done — ${state.succeeded} created, ${state.skipped} skipped, ${state.failed} failed"
                else -> ""
            },
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    ImportRowPreviewList(rows = state.rows, showStatus = true)
}

@Composable
private fun ImportRowPreviewList(
    rows: List<DeploymentImportRowUi>,
    showStatus: Boolean = false
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkBackground)
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(rows, key = { _, row -> "${row.lineNumber}-${row.symbol}" }) { _, row ->
            ImportRowLine(row = row, showStatus = showStatus)
        }
    }
}

@Composable
private fun ImportRowLine(row: DeploymentImportRowUi, showStatus: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showStatus) {
            ImportRowStatusIcon(status = row.status)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${row.symbol} · ${row.marketLabel}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            row.detail?.let { detail ->
                Text(detail, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
            }
            row.companyName?.takeIf { row.status == DeploymentImportRowStatus.SUCCESS }?.let { name ->
                Text(name, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ImportRowStatusIcon(status: DeploymentImportRowStatus) {
    when (status) {
        DeploymentImportRowStatus.PENDING -> Spacer(modifier = Modifier.size(16.dp))
        DeploymentImportRowStatus.RESOLVING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = TextSecondary
        )
        DeploymentImportRowStatus.SUCCESS -> androidx.compose.material3.Icon(
            Icons.Default.Check,
            contentDescription = "Imported",
            tint = GainGreen,
            modifier = Modifier.size(16.dp)
        )
        DeploymentImportRowStatus.SKIPPED -> androidx.compose.material3.Icon(
            Icons.Default.Close,
            contentDescription = "Skipped",
            tint = TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        DeploymentImportRowStatus.FAILED -> androidx.compose.material3.Icon(
            Icons.Default.Close,
            contentDescription = "Failed",
            tint = BrandRed,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ImportActionRow(
    state: DeploymentSymbolImportUiState,
    onDismiss: () -> Unit,
    onStartImport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onDismiss,
            enabled = state.canDismiss
        ) {
            Text(
                if (state.phase == DeploymentImportPhase.COMPLETE) "Close" else "Cancel",
                color = if (state.canDismiss) TextSecondary else TextSecondary.copy(alpha = 0.4f)
            )
        }
        if (state.phase == DeploymentImportPhase.CONFIG) {
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                onClick = onStartImport,
                enabled = state.canStartImport,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("StartSymbolImportButton")
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("Import ${state.pendingImportCount} symbol(s)")
            }
        }
    }
}
