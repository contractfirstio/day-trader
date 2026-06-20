package daytrader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import daytrader.data.PortfolioExposureCalculator

@Composable
fun GlobalKillSwitchDialog(
    exposure: PortfolioExposureCalculator.Snapshot,
    runningSymbols: List<String>,
    orphanBrokerActivity: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activate kill switch?") },
        text = {
            Text(
                buildString {
                    appendLine("This will:")
                    appendLine("• Stop all running strategy sessions")
                    appendLine("• Cancel working orders and flatten open positions at the broker")
                    if (runningSymbols.isNotEmpty()) {
                        appendLine()
                        append("Running: ${runningSymbols.joinToString()}")
                        appendLine()
                    }
                    appendLine("Total max at risk: $${exposure.totalMaxAtRiskUsd}")
                    if (orphanBrokerActivity) {
                        appendLine()
                        append("Also flattens broker symbols outside running sessions.")
                    }
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Kill switch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
