package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.watchlist.WatchlistReversalScoreInsightUi
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchlistReversalScoreInsightSheet(
    insight: WatchlistReversalScoreInsightUi,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        modifier = Modifier.testTag("WatchlistReversalScoreInsightSheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = insight.symbol,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = insight.companyName,
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = insight.compositeScore.toString(),
                    color = reversalScoreAccent(insight.compositeScore),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                insight.contextBadgeLabel?.let { badgeLabel ->
                    Text(
                        text = badgeLabel,
                        color = alignmentBadgeAccent(badgeLabel),
                        fontWeight = alignmentBadgeFontWeight(badgeLabel),
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = DarkBackground)
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "The Thinking",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = insight.insightText,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "The Recommendation",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = insight.recommendationText,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
