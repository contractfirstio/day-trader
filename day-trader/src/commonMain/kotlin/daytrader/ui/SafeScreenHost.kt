package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiFault
import daytrader.presentation.ui.UiFaultBus
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SessionErrorBorder
import daytrader.ui.theme.SessionErrorSurface
import daytrader.ui.theme.TextSecondary

@Composable
fun SafeScreenHost(
    screen: AppScreen,
    retryNonce: Int,
    onRetry: () -> Unit,
    onGoToStrategies: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val faults by UiFaultBus.faults.collectAsState()
    val fault = faults[screen]

    Column(modifier = modifier.fillMaxSize()) {
        fault?.let {
            ScreenFaultBanner(
                fault = it,
                onRetry = onRetry,
                onGoToStrategies = onGoToStrategies,
            )
        }
        key(screen, retryNonce) {
            content()
        }
    }
}

@Composable
private fun ScreenFaultBanner(
    fault: UiFault,
    onRetry: () -> Unit,
    onGoToStrategies: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ScreenFaultBanner"),
        color = SessionErrorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, SessionErrorBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "This screen hit an error and may be out of date.",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SessionErrorBorder,
            )
            Text(
                text = fault.message,
                fontSize = 12.sp,
                color = TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = GainGreen),
                    modifier = Modifier.testTag("ScreenFaultRetryButton"),
                ) {
                    Text("Retry")
                }
                OutlinedButton(
                    onClick = onGoToStrategies,
                    modifier = Modifier.testTag("ScreenFaultGoToStrategiesButton"),
                ) {
                    Text("Go to Strategies")
                }
            }
        }
    }
}
