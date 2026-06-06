package daytrader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import daytrader.presentation.watchlist.WatchlistDiaryNotificationUi
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary
import daytrader.ui.theme.TradeBlueBorder

@Composable
fun WatchlistPlanDiaryNotificationDialog(
    notification: WatchlistDiaryNotificationUi,
    onView: () -> Unit,
    onDismissReminder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissReminder,
        modifier = Modifier.testTag("WatchlistPlanDiaryNotificationDialog"),
        containerColor = SurfaceDark,
        title = {
            Text("Plan diary reminder", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                buildString {
                    appendLine("${notification.symbol} · ${notification.planLabel}")
                    appendLine("Due since ${notification.notifyOnDateLabel}")
                    appendLine()
                    append(notification.bodyPreview.ifBlank { "(No preview)" })
                },
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onView,
                colors = ButtonDefaults.buttonColors(containerColor = TradeBlueBorder),
                modifier = Modifier.testTag("ViewWatchlistPlanDiaryNotificationButton")
            ) {
                Text("View diary entry", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissReminder,
                modifier = Modifier.testTag("DismissWatchlistPlanDiaryNotificationButton")
            ) {
                Text("Dismiss reminder", color = BrandRed)
            }
        }
    )
}
