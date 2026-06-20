package daytrader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import daytrader.diagnostics.AppHealthSnapshot

@Composable
fun DebugHealthDialog(
    snapshot: AppHealthSnapshot,
    exportPath: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App health snapshot") },
        text = {
            Text(
                buildString {
                    appendLine("Broker: ${snapshot.brokerKind}")
                    appendLine("Execution: ${snapshot.executionConnection}")
                    snapshot.marketDataConnection?.let { appendLine("Market data: $it") }
                    appendLine("Running sessions: ${snapshot.runningSessionCount}")
                    if (snapshot.runningSessionSymbols.isNotEmpty()) {
                        appendLine("Symbols: ${snapshot.runningSessionSymbols.joinToString()}")
                    }
                    appendLine("Quotes: ${snapshot.activeQuoteCount}")
                    appendLine("Open orders: ${snapshot.openOrderCount}")
                    appendLine("Open positions: ${snapshot.openPositionCount}")
                    appendLine("Data dir: ${snapshot.dataDirectory}")
                    exportPath?.let { appendLine("Exported: $it") }
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
