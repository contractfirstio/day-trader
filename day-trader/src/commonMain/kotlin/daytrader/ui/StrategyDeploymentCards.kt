package daytrader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.data.StrategyCatalog
import daytrader.domain.*
import daytrader.presentation.strategies.*
import daytrader.ui.theme.*

@Composable
internal fun animatedCardPulseAlpha(accent: DeploymentCardAccent): Float {
    if (!accent.isPulsing) return 1f
    val transition = rememberInfiniteTransition(label = "instanceCardPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(750), repeatMode = RepeatMode.Reverse),
        label = "instanceCardPulseAlpha"
    )
    return alpha
}

@Composable
internal fun InstanceCardChrome(
    accent: DeploymentCardAccent,
    isSelected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val style = instanceCardStyle(accent)
    val pulseAlpha = animatedCardPulseAlpha(accent)
    val accentBorder = style.borderColor.copy(alpha = style.borderColor.alpha * pulseAlpha)
    val borderColor = if (isSelected) BrandRed else accentBorder
    val borderWidth = if (isSelected) 2.dp else style.borderWidth
    Box(
        modifier = modifier
            .border(borderWidth, borderColor, shape)
            .background(style.surfaceBrush, shape)
    ) {
        content()
    }
}

internal data class InstanceCardStyle(
    val borderWidth: Dp,
    val borderColor: Color,
    val surfaceBrush: Brush
)

internal fun instanceCardStyle(accent: DeploymentCardAccent): InstanceCardStyle = when (accent) {
    DeploymentCardAccent.ERROR -> InstanceCardStyle(
        2.dp,
        SessionErrorBorder.copy(alpha = 0.9f),
        Brush.verticalGradient(listOf(SessionErrorSurface, SessionErrorGlow))
    )
    DeploymentCardAccent.STOPPED_IDLE -> InstanceCardStyle(
        1.dp,
        TableHeaderBg,
        Brush.linearGradient(listOf(SurfaceDark, SurfaceDark))
    )
    DeploymentCardAccent.STOPPED_NEUTRAL -> InstanceCardStyle(
        2.dp,
        TradeNeutralBorder,
        Brush.verticalGradient(listOf(TradeNeutralSurface, TradeNeutralGlow))
    )
    DeploymentCardAccent.STOPPED_WIN -> InstanceCardStyle(
        2.dp,
        MarketOpenBorder.copy(alpha = 0.9f),
        Brush.verticalGradient(listOf(MarketOpenSurface, MarketOpenGlow))
    )
    DeploymentCardAccent.STOPPED_LOSS -> InstanceCardStyle(
        2.dp,
        TradeRedBorder.copy(alpha = 0.9f),
        Brush.verticalGradient(listOf(TradeRedSurface, TradeRedGlow))
    )
    DeploymentCardAccent.RUNNING_FLAT -> InstanceCardStyle(
        2.dp,
        TradeBlueBorder.copy(alpha = 0.85f),
        Brush.verticalGradient(listOf(TradeBlueSurface, TradeBlueGlow))
    )
    DeploymentCardAccent.RUNNING_IN_THE_MONEY -> InstanceCardStyle(
        2.dp,
        MarketOpenBorder,
        Brush.verticalGradient(listOf(MarketOpenSurface, MarketOpenGlow))
    )
    DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY -> InstanceCardStyle(
        2.dp,
        TradeRedBorder,
        Brush.verticalGradient(listOf(TradeRedSurface, TradeRedGlow))
    )
    DeploymentCardAccent.OPEN_ORDERS -> InstanceCardStyle(
        2.dp,
        OpenOrdersBrownBorder,
        Brush.verticalGradient(listOf(OpenOrdersBrownSurface, OpenOrdersBrownGlow))
    )
}

internal fun instanceChipColor(accent: DeploymentCardAccent): Color = when (accent) {
    DeploymentCardAccent.ERROR -> SessionErrorBorder
    DeploymentCardAccent.STOPPED_IDLE -> TextSecondary
    DeploymentCardAccent.STOPPED_NEUTRAL -> TradeNeutralBorder
    DeploymentCardAccent.STOPPED_WIN,
    DeploymentCardAccent.RUNNING_IN_THE_MONEY -> MarketOpenBorder
    DeploymentCardAccent.RUNNING_FLAT -> TradeBlueBorder
    DeploymentCardAccent.STOPPED_LOSS,
    DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY -> TradeRedBorder
    DeploymentCardAccent.OPEN_ORDERS -> OpenOrdersBrownBorder
}

@Composable
internal fun InstanceStateChip(
    label: String,
    accent: DeploymentCardAccent,
    compact: Boolean = false
) {
    val baseColor = instanceChipColor(accent)
    val pulseAlpha = animatedCardPulseAlpha(accent)
    val color = baseColor.copy(alpha = baseColor.alpha * pulseAlpha)
    val dotSize = if (compact) 5.dp else 8.dp
    val fontSize = if (compact) 9.sp else 12.sp
    val spacing = if (compact) 4.dp else 6.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .background(color, RoundedCornerShape(50))
        )
        Text(
            label,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
internal fun StrategiesHeader(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onAddInstance: () -> Unit,
    onImportSymbols: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Strategies",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text("Search…", color = TextSecondary, fontSize = 11.sp)
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .width(200.dp)
                    .height(32.dp)
                    .testTag("StrategySearchField")
            )
            Button(
                onClick = onImportSymbols,
                colors = ButtonDefaults.buttonColors(containerColor = TableHeaderBg),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("ImportStrategyDeploymentsButton")
            ) {
                Icon(
                    Icons.Default.Upload,
                    contentDescription = "Import",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import", color = Color.White, fontSize = 11.sp)
            }
            Button(
                onClick = onAddInstance,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("AddStrategyDeploymentButton")
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Deploy", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) BrandRed.copy(alpha = 0.25f) else DarkBackground
    val borderColor = if (selected) BrandRed else TableHeaderBg
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (selected) Color.White else TextSecondary,
        fontSize = 10.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1
    )
}

@Composable
internal fun CompactAutoStartToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        checked -> GainGreen
        else -> TextSecondary
    }
    IconButton(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = modifier
            .size(20.dp)
            .testTag("AutoStartToggle")
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = if (checked) "Auto-start on" else "Auto-start off",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
internal fun StrategyDeploymentCard(
    row: StrategyDeploymentRowUi,
    isSelected: Boolean,
    globalAutoStartEnabled: Boolean,
    onSelect: () -> Unit,
    onToggleSession: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit
) {
    val cardShape = RoundedCornerShape(6.dp)
    InstanceCardChrome(
        accent = row.cardAccent,
        isSelected = isSelected,
        shape = cardShape,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("StrategyDeploymentCard-${row.id}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.instrumentName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.instrumentName != row.name) {
                        Text(
                            row.name,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                CompactAutoStartToggle(
                    checked = row.autoStartOnMarketOpen,
                    enabled = globalAutoStartEnabled,
                    onCheckedChange = onAutoStartChange
                )
                IconButton(
                    onClick = onToggleSession,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (row.status == DeploymentStatus.RUNNING) {
                            Icons.Default.Stop
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (row.status == DeploymentStatus.RUNNING) "Stop" else "Start",
                        tint = if (row.status == DeploymentStatus.RUNNING) LossRed else GainGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InstanceStateChip(
                    label = row.statusChipLabel,
                    accent = row.cardAccent,
                    compact = true
                )
                Spacer(modifier = Modifier.weight(1f))
                if (row.hasOpenPosition && row.formattedPositionPnL != null) {
                    CompactInstanceStat(
                        label = "Position",
                        value = row.formattedPositionPnL,
                        valueColor = if (row.isPositivePositionPnL == true) GainGreen else LossRed
                    )
                }
                CompactInstanceStat(
                    label = "Win %",
                    value = row.formattedWinRate,
                    valueColor = winRateColor(row.winRateIsPositive)
                )
                CompactInstanceStat(
                    label = "Net P&L",
                    value = row.formattedTotalPnL,
                    valueColor = if (row.isPositiveTotalPnL) GainGreen else LossRed
                )
            }
        }
    }
}

internal fun winRateColor(winRateIsPositive: Boolean?): Color = when (winRateIsPositive) {
    true -> MarketOpenBorder
    false -> TradeRedBorder
    null -> TextSecondary
}

@Composable
internal fun CompactInstanceStat(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 8.sp, color = TextSecondary, lineHeight = 9.sp)
        Text(
            value,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            lineHeight = 11.sp
        )
    }
}

@Composable
internal fun InstanceRollupRow(row: StrategyDeploymentRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        InstanceRollupCell("7D", row.formattedRollup7d, row.isPositiveRollup7d)
        InstanceRollupCell("14D", row.formattedRollup14d, row.isPositiveRollup14d)
        InstanceRollupCell("30D", row.formattedRollup30d, row.isPositiveRollup30d)
        InstanceRollupCell("Win %", row.formattedWinRate)
    }
}

@Composable
internal fun RowScope.InstanceRollupCell(label: String, value: String, positive: Boolean? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(label, fontSize = 10.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = when (positive) {
                true -> GainGreen
                false -> LossRed
                null -> if (value == "—") TextSecondary else Color.White
            }
        )
    }
}

@Composable
internal fun StrategyTypePill(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .background(BrandRed.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = BrandRed,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
}
