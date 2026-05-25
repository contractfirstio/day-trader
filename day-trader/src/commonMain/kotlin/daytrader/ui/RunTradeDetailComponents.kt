package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.strategies.RunTradeDetailUiState
import daytrader.presentation.strategies.RunTradeFillUi
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun RunTradeDetailPanel(
    detail: RunTradeDetailUiState,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "RunTradeDetail"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}Panel")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = detail.sideLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (detail.isLong) GainGreen else LossRed,
                modifier = Modifier.testTag("${testTagPrefix}Side")
            )
            Text(
                text = detail.formattedRealizedPnL,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (detail.isPositiveRealizedPnL) GainGreen else LossRed,
                modifier = Modifier.testTag("${testTagPrefix}RealizedPnL")
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detail.headline,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.testTag("${testTagPrefix}Headline")
        )
        Text(
            text = detail.detailLine,
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.testTag("${testTagPrefix}DetailLine")
        )
        detail.lifecycleLabel?.let { label ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = Color(0xFFFFB74D),
                modifier = Modifier.testTag("${testTagPrefix}Lifecycle")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        detail.formattedUnrealizedPnL?.let { unrealized ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Unrealized", fontSize = 10.sp, color = TextSecondary)
                Text(
                    unrealized,
                    fontSize = 10.sp,
                    color = if ((detail.unrealizedPnL ?: 0.0) >= 0) GainGreen else LossRed,
                    modifier = Modifier.testTag("${testTagPrefix}Unrealized")
                )
            }
        }
        detail.formattedSessionPnL?.let { session ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Session P&L", fontSize = 10.sp, color = TextSecondary)
                Text(
                    session,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (detail.isPositiveSessionPnL == true) GainGreen else LossRed,
                    modifier = Modifier.testTag("${testTagPrefix}SessionPnL")
                )
            }
        }
        if (detail.fills.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Fills", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            detail.fills.forEachIndexed { index, fill ->
                if (index > 0) {
                    HorizontalDivider(color = TableHeaderBg.copy(alpha = 0.6f))
                }
                RunTradeFillRow(fill, testTagPrefix)
            }
        }
    }
}

@Composable
private fun RunTradeFillRow(fill: RunTradeFillUi, testTagPrefix: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("${testTagPrefix}Fill_${fill.execId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    fill.roleLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF90CAF9),
                    modifier = Modifier
                        .background(Color(0xFF1E3A5F), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
                Text(
                    "${fill.sideLabel} ${fill.quantity} @ ${fill.formattedPrice}",
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
            Text(fill.formattedTime, fontSize = 9.sp, color = TextSecondary)
        }
        fill.formattedRealizedPnL?.let { pnl ->
            Text(
                pnl,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (fill.isPositivePnL) GainGreen else LossRed
            )
        }
    }
}
