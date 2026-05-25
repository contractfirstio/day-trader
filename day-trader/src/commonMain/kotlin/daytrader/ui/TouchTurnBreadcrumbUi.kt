package daytrader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.strategies.TouchTurnBreadcrumbStep
import daytrader.presentation.strategies.TouchTurnBreadcrumbStepState
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TextSecondary

@Composable
fun TouchTurnStatusBreadcrumbRow(
    steps: List<TouchTurnBreadcrumbStep>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Text(
                    "›",
                    fontSize = 10.sp,
                    color = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.testTag("TouchTurnBreadcrumbSep$index")
                )
            }
            val color = when (step.state) {
                TouchTurnBreadcrumbStepState.COMPLETED -> GainGreen
                TouchTurnBreadcrumbStepState.CURRENT -> Color.White
                TouchTurnBreadcrumbStepState.FAILED -> LossRed
                TouchTurnBreadcrumbStepState.SKIPPED -> TextSecondary.copy(alpha = 0.35f)
                TouchTurnBreadcrumbStepState.UPCOMING -> TextSecondary.copy(alpha = 0.55f)
            }
            val weight = when (step.state) {
                TouchTurnBreadcrumbStepState.CURRENT -> FontWeight.SemiBold
                TouchTurnBreadcrumbStepState.FAILED -> FontWeight.SemiBold
                else -> FontWeight.Normal
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.testTag("TouchTurnBreadcrumb_${step.label}")
            ) {
                Text(
                    step.label,
                    fontSize = 10.sp,
                    fontWeight = weight,
                    color = color,
                    maxLines = 1
                )
                step.timestamp?.let { time ->
                    Text(
                        time,
                        fontSize = 8.sp,
                        color = TextSecondary.copy(alpha = 0.75f),
                        maxLines = 1,
                        modifier = Modifier.testTag("TouchTurnBreadcrumbTime_${step.label}")
                    )
                }
            }
        }
    }
}
