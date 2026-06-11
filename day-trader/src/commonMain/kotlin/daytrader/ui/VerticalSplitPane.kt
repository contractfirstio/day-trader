package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.TableHeaderBg

@Composable
fun VerticalSplitPane(
    modifier: Modifier = Modifier,
    initialTopFraction: Float = 0.5f,
    minTopFraction: Float = 0.25f,
    maxTopFraction: Float = 0.75f,
    dividerHeight: Dp = 10.dp,
    topContent: @Composable BoxScope.() -> Unit,
    bottomContent: @Composable BoxScope.() -> Unit
) {
    var topFraction by rememberSaveable { mutableFloatStateOf(initialTopFraction) }

    BoxWithConstraints(modifier = modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val dividerHeightPx = with(density) { dividerHeight.toPx() }
        val availableHeightPx = with(density) { maxHeight.toPx() } - dividerHeightPx
        val clampedFraction = topFraction.coerceIn(minTopFraction, maxTopFraction)
        if (clampedFraction != topFraction) {
            topFraction = clampedFraction
        }
        val topHeight = with(density) { (availableHeightPx * clampedFraction).toDp() }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topHeight)
            ) {
                topContent()
            }

            VerticalSplitPaneDivider(
                height = dividerHeight,
                onDrag = { deltaPx ->
                    if (availableHeightPx > 0f) {
                        topFraction = (topFraction + deltaPx / availableHeightPx)
                            .coerceIn(minTopFraction, maxTopFraction)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                bottomContent()
            }
        }
    }
}

@Composable
private fun VerticalSplitPaneDivider(
    height: Dp,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(height)
            .testTag("VerticalSplitPaneDivider")
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { _, dragAmount -> onDrag(dragAmount) }
                )
            }
            .background(
                if (isDragging) BrandRed.copy(alpha = 0.35f) else TableHeaderBg.copy(alpha = 0.45f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (isDragging) BrandRed else TableHeaderBg)
        )
    }
}
