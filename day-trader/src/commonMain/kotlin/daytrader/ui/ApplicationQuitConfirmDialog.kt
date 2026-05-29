package daytrader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

/**
 * Shown when the user tries to quit while [runningSymbols] is non-empty.
 */
@Composable
fun ApplicationQuitConfirmDialog(
    runningSymbols: List<String>,
    onConfirmQuit: () -> Unit,
    onDismiss: () -> Unit
) {
    val count = runningSymbols.size
    val symbolList = runningSymbols.joinToString(", ")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Quit Day Trader?", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                buildQuitWarningMessage(count = count, symbolList = symbolList),
                color = TextSecondary,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmQuit,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
            ) {
                Text("Quit and stop sessions")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

internal fun buildQuitWarningMessage(count: Int, symbolList: String): String {
    val sessionWord = if (count == 1) "session" else "sessions"
    return buildString {
        append("You have $count running strategy $sessionWord")
        if (symbolList.isNotBlank()) {
            append(" ($symbolList)")
        }
        append(
            ". Quitting will stop those sessions, cancel any working orders, " +
                "and close open positions at market."
        )
    }
}
