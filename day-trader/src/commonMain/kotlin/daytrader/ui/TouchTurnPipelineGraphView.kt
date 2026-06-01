package daytrader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.strategies.TouchTurnBreadcrumbStep
import daytrader.presentation.strategies.TouchTurnBreadcrumbStepState
import daytrader.presentation.strategies.TouchTurnReasonSeverity
import daytrader.ui.theme.LossRed
import daytrader.presentation.strategies.TouchTurnPipelineEdgeState
import daytrader.presentation.strategies.TouchTurnPipelineGraph
import daytrader.presentation.strategies.TouchTurnPipelineNode
import daytrader.presentation.strategies.TouchTurnPipelineNodeId
import daytrader.presentation.strategies.detailTitle
import daytrader.presentation.strategies.isSelectable
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun TouchTurnPipelineGraphView(
    graph: TouchTurnPipelineGraph,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showTitle: Boolean = true,
    selectedNodeId: TouchTurnPipelineNodeId? = null,
    onNodeSelected: ((TouchTurnPipelineNodeId) -> Unit)? = null
) {
    val graphHeight = if (compact) 88.dp else 104.dp
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nodeRadiusPx = with(density) { (if (compact) 7.dp else 8.dp).toPx() }
    val hitSize = if (compact) 36.dp else 40.dp
    val labelStyle = TextStyle(
        fontSize = if (compact) 8.sp else 9.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary
    )
    val edgeLabelStyle = TextStyle(
        fontSize = 7.sp,
        color = TextSecondary.copy(alpha = 0.85f)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("TouchTurnPipelineGraph"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        if (showTitle) {
            Text(
                "Session pipeline",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandRed,
                modifier = Modifier.testTag("TouchTurnPipelineGraphTitle")
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .testTag("TouchTurnPipelineGraphCanvas")
        ) {
            val canvasModifier = Modifier.fillMaxSize()
            Canvas(modifier = canvasModifier) {
                val nodeCenters = graph.nodes.associate { node ->
                    node.id to Offset(node.x * size.width, node.y * size.height)
                }
                graph.edges.forEach { edge ->
                    val from = nodeCenters[edge.from] ?: return@forEach
                    val to = nodeCenters[edge.to] ?: return@forEach
                    drawPipelineEdge(
                        from = from,
                        to = to,
                        state = edge.state,
                        label = edge.label,
                        textMeasurer = textMeasurer,
                        edgeLabelStyle = edgeLabelStyle
                    )
                }
                graph.nodes.forEach { node ->
                    val center = nodeCenters[node.id] ?: return@forEach
                    drawPipelineNode(
                        node = node,
                        center = center,
                        radius = nodeRadiusPx,
                        compact = compact,
                        isSelected = node.id == selectedNodeId
                    )
                }
                graph.nodes.forEach { node ->
                    val center = nodeCenters[node.id] ?: return@forEach
                    val labelResult = textMeasurer.measure(node.shortLabel, labelStyle)
                    val labelY = center.y + nodeRadiusPx + with(density) { 4.dp.toPx() }
                    drawText(
                        textLayoutResult = labelResult,
                        topLeft = Offset(
                            x = center.x - labelResult.size.width / 2f,
                            y = labelY
                        )
                    )
                }
            }
            if (onNodeSelected != null) {
                graph.nodes.filter { it.isSelectable() }.forEach { node ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = maxWidth * node.x - hitSize / 2,
                                y = maxHeight * node.y - hitSize / 2
                            )
                            .size(hitSize)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onNodeSelected(node.id) }
                            .testTag("TouchTurnPipelineNodeHit_${node.id.name}")
                    )
                }
            }
        }
        val detailHint = selectedNodeId?.detailTitle()
        when {
            detailHint != null -> {
                Text(
                    detailHint,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .testTag("TouchTurnPipelineGraphSelectedLabel")
                )
            }
            graph.statusBanner != null -> {
                Text(
                    graph.statusBanner.headline,
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = when (graph.statusBanner.severity) {
                        TouchTurnReasonSeverity.Error -> LossRed
                        TouchTurnReasonSeverity.Warning -> Color(0xFFFFB74D)
                        TouchTurnReasonSeverity.Info -> TextSecondary
                    },
                    maxLines = 2,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .testTag("TouchTurnPipelineGraphStatusBanner")
                )
            }
            graph.caption.isNotBlank() -> {
                Text(
                    graph.caption,
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .testTag("TouchTurnPipelineGraphCaption")
                )
            }
        }
    }
}

private fun DrawScope.drawPipelineEdge(
    from: Offset,
    to: Offset,
    state: TouchTurnPipelineEdgeState,
    label: String?,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    edgeLabelStyle: TextStyle
) {
    val color = when (state) {
        TouchTurnPipelineEdgeState.Taken -> GainGreen.copy(alpha = 0.85f)
        TouchTurnPipelineEdgeState.Active -> Color.White
        TouchTurnPipelineEdgeState.Dimmed -> TextSecondary.copy(alpha = 0.35f)
        TouchTurnPipelineEdgeState.Unreachable -> TableHeaderBg.copy(alpha = 0.55f)
    }
    val strokeWidth = when (state) {
        TouchTurnPipelineEdgeState.Active -> 2.5f
        TouchTurnPipelineEdgeState.Taken -> 2f
        else -> 1.25f
    }
    val path = Path().apply {
        moveTo(from.x, from.y)
        val midX = (from.x + to.x) / 2f
        val midY = (from.y + to.y) / 2f
        if (kotlin.math.abs(from.y - to.y) > 8f) {
            quadraticTo(midX, from.y, midX, midY)
            quadraticTo(midX, to.y, to.x, to.y)
        } else {
            lineTo(to.x, to.y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth)
    )
    label?.let { text ->
        if (state == TouchTurnPipelineEdgeState.Dimmed ||
            state == TouchTurnPipelineEdgeState.Unreachable
        ) {
            return@let
        }
        val mid = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
        val layout = textMeasurer.measure(text, edgeLabelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                mid.x - layout.size.width / 2f,
                mid.y - layout.size.height / 2f - 6f
            )
        )
    }
}

private fun DrawScope.drawPipelineNode(
    node: TouchTurnPipelineNode,
    center: Offset,
    radius: Float,
    compact: Boolean,
    isSelected: Boolean
) {
    val fillColor = when (node.state) {
        TouchTurnBreadcrumbStepState.COMPLETED -> GainGreen.copy(alpha = 0.25f)
        TouchTurnBreadcrumbStepState.CURRENT -> BrandRed.copy(alpha = 0.35f)
        TouchTurnBreadcrumbStepState.FAILED -> LossRed.copy(alpha = 0.35f)
        TouchTurnBreadcrumbStepState.SKIPPED -> Color.Transparent
        TouchTurnBreadcrumbStepState.UPCOMING -> TableHeaderBg.copy(alpha = 0.9f)
    }
    val borderColor = when {
        isSelected -> BrandRed
        node.state == TouchTurnBreadcrumbStepState.COMPLETED -> GainGreen
        node.state == TouchTurnBreadcrumbStepState.CURRENT -> Color.White
        node.state == TouchTurnBreadcrumbStepState.FAILED -> LossRed
        node.state == TouchTurnBreadcrumbStepState.SKIPPED -> TextSecondary.copy(alpha = 0.25f)
        else -> TextSecondary.copy(alpha = 0.45f)
    }
    val borderWidth = when {
        isSelected -> if (compact) 3f else 3.5f
        node.state == TouchTurnBreadcrumbStepState.CURRENT -> if (compact) 2.5f else 3f
        node.state == TouchTurnBreadcrumbStepState.FAILED -> 2.5f
        else -> 1.5f
    }
    if (node.isDecision && node.state != TouchTurnBreadcrumbStepState.SKIPPED) {
        val diamond = Path().apply {
            moveTo(center.x, center.y - radius * 1.15f)
            lineTo(center.x + radius * 1.15f, center.y)
            lineTo(center.x, center.y + radius * 1.15f)
            lineTo(center.x - radius * 1.15f, center.y)
            close()
        }
        drawPath(diamond, fillColor)
        drawPath(diamond, borderColor, style = Stroke(width = borderWidth))
    } else if (node.state != TouchTurnBreadcrumbStepState.SKIPPED) {
        drawCircle(color = fillColor, radius = radius, center = center)
        drawCircle(color = borderColor, radius = radius, center = center, style = Stroke(width = borderWidth))
    } else {
        drawCircle(
            color = borderColor,
            radius = radius * 0.85f,
            center = center,
            style = Stroke(width = 1f)
        )
    }
    if (node.state == TouchTurnBreadcrumbStepState.COMPLETED) {
        drawCircle(color = GainGreen, radius = radius * 0.35f, center = center)
    }
}

/** @deprecated Use [TouchTurnPipelineGraphView] */
@Composable
fun TouchTurnStatusBreadcrumbRow(
    steps: List<TouchTurnBreadcrumbStep>,
    modifier: Modifier = Modifier
) {
    TouchTurnPipelineGraphView(
        graph = daytrader.presentation.strategies.TouchTurnStatusBreadcrumbMapper.buildGraph(steps),
        modifier = modifier,
        compact = true,
        showTitle = false
    )
}
