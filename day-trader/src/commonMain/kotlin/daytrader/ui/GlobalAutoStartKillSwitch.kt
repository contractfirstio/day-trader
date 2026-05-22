package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TextSecondary
import daytrader.ui.theme.TradeRedSurface

@Composable
fun GlobalAutoStartKillSwitch(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val containerModifier = if (enabled) {
        modifier
    } else {
        modifier
            .background(TradeRedSurface, shape)
            .border(1.dp, LossRed.copy(alpha = 0.85f), shape)
    }
    Row(
        modifier = containerModifier.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = if (enabled) "Auto-start all" else "Auto-start OFF",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) Color.White else LossRed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            if (!enabled) {
                Text(
                    text = "No instances will start at market open",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 11.sp
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.testTag("GlobalAutoStartKillSwitch"),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GainGreen,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BrandRed,
                uncheckedBorderColor = LossRed
            )
        )
    }
}
