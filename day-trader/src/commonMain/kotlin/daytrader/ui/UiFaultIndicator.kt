package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import daytrader.presentation.navigation.displayLabel
import daytrader.presentation.ui.UiFault
import daytrader.ui.theme.SessionErrorBorder
import daytrader.ui.theme.SessionErrorSurface

@Composable
fun UiFaultIndicator(
    faults: List<UiFault>,
    onResetUi: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (faults.isEmpty()) return
    val label = when (faults.size) {
        1 -> "Screen issue: ${faults.first().screen.displayLabel()}"
        else -> "${faults.size} screens need attention"
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.testTag("UiFaultIndicator"),
            color = SessionErrorSurface,
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, SessionErrorBorder),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = SessionErrorBorder,
            )
        }
        if (onResetUi != null) {
            TextButton(
                onClick = onResetUi,
                modifier = Modifier.testTag("UiFaultResetButton"),
            ) {
                Text("Reset UI", color = SessionErrorBorder)
            }
        }
    }
}
