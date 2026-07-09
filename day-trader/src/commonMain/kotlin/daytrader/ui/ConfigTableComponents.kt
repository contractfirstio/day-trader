package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

internal val ConfigTableLabelWidth: Dp = 128.dp

/** Which config-tab table category a deployment market block should render. */
internal enum class DeploymentConfigCategory {
    Instrument,
    MarketSession,
    All
}

@Composable
internal fun ConfigTableSection(
    title: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        HorizontalDivider(color = TableHeaderBg)
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

@Composable
internal fun ConfigTableRow(
    label: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = ConfigTableLabelWidth,
    alignTop: Boolean = false,
    testTag: String? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(labelWidth)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            content()
        }
    }
}

@Composable
internal fun ConfigTableValueText(
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    color: Color = Color.White,
    testTag: String? = null
) {
    Text(
        value,
        modifier = modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        fontSize = if (emphasized) 12.sp else 11.sp,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
        color = color,
        lineHeight = 14.sp
    )
}
