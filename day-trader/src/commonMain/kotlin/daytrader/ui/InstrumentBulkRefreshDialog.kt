package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import daytrader.presentation.strategies.DeploymentImportRowStatus
import daytrader.presentation.strategies.InstrumentBulkRefreshPhase
import daytrader.presentation.strategies.InstrumentBulkRefreshRowUi
import daytrader.presentation.strategies.InstrumentBulkRefreshUiState
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
internal fun InstrumentBulkRefreshDialog(
    state: InstrumentBulkRefreshUiState,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    Dialog(onDismissRequest = { if (state.canDismiss) onDismiss() }) {
        Surface(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 520.dp)
                .testTag("InstrumentBulkRefreshDialog"),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Refresh instrument data",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (state.scopeLabel.isNotBlank()) {
                    Text(
                        state.scopeLabel,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    "Re-fetch IB contract details for deployments matching the current filter: board lot, min tick, and price tick bands. Running deployments are skipped.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                if (!state.brokerConnected) {
                    Text(
                        "Connect to Interactive Brokers first.",
                        color = LossRed,
                        fontSize = 12.sp
                    )
                }
                if (state.phase != InstrumentBulkRefreshPhase.CONFIRM) {
                    LinearProgressIndicator(
                        progress = {
                            if (state.total <= 0) 0f else state.completed.toFloat() / state.total.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = BrandRed,
                        trackColor = TableHeaderBg
                    )
                    Text(
                        "${state.completed}/${state.total} · ${state.succeeded} ok · ${state.failed} failed · ${state.skipped} skipped",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.rows, key = { it.deploymentId }) { row ->
                        InstrumentBulkRefreshRow(row)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = state.canDismiss
                    ) {
                        Text(if (state.phase == InstrumentBulkRefreshPhase.COMPLETE) "Close" else "Cancel")
                    }
                    if (state.phase == InstrumentBulkRefreshPhase.CONFIRM) {
                        Button(
                            onClick = onStart,
                            enabled = state.canStart,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                        ) {
                            Text("Refresh filtered")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstrumentBulkRefreshRow(row: InstrumentBulkRefreshRowUi) {
    val statusColor = when (row.status) {
        DeploymentImportRowStatus.SUCCESS -> GainGreen
        DeploymentImportRowStatus.FAILED -> LossRed
        DeploymentImportRowStatus.SKIPPED -> TextSecondary
        DeploymentImportRowStatus.RESOLVING -> Color.White
        DeploymentImportRowStatus.PENDING -> TextSecondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.symbol, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            Text(row.status.name.lowercase(), color = statusColor, fontSize = 11.sp)
            row.detail?.let {
                Text(it, color = TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
