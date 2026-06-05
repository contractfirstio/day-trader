package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.ResolvedInstrument
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun InstrumentListingPicker(
    candidates: List<ResolvedInstrument>,
    selected: ResolvedInstrument?,
    onSelect: (ResolvedInstrument) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.testTag("InstrumentListingPicker")
    ) {
        Text(
            "Listing / exchange",
            fontSize = 11.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
        Text(
            if (selected == null) {
                "Multiple venues found — select one to continue."
            } else {
                "Selected listing:"
            },
            fontSize = 12.sp,
            color = if (selected == null) LossRed else TextSecondary,
            lineHeight = 15.sp
        )
        candidates.forEach { candidate ->
            val label = InstrumentListingCandidates.listingLabel(candidate)
            val picked = selected?.identity?.dedupeKey() == candidate.identity?.dedupeKey()
            val borderColor = if (picked) BrandRed else TableHeaderBg
            val background = if (picked) BrandRed.copy(alpha = 0.2f) else DarkBackground
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(background)
                    .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                    .clickable { onSelect(candidate) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("InstrumentListingOption-${candidate.identity?.dedupeKey()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(
                    selected = picked,
                    onClick = { onSelect(candidate) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = BrandRed,
                        unselectedColor = TextSecondary
                    )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    candidate.companyName?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(name, fontSize = 12.sp, color = TextSecondary, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}
