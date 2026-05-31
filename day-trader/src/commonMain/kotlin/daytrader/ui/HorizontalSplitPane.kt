package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.TableHeaderBg

@Composable
fun HorizontalSplitPane(
    modifier: Modifier = Modifier,
    initialLeftFraction: Float = 0.25f,
    minLeftFraction: Float = 0.2f,
    maxLeftFraction: Float = 0.75f,
    dividerWidth: Dp = 10.dp,
    leftContent: @Composable BoxScope.() -> Unit,
    rightContent: @Composable BoxScope.() -> Unit
) {
    var leftFraction by rememberSaveable { mutableFloatStateOf(initialLeftFraction) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val dividerWidthPx = with(density) { dividerWidth.toPx() }
        val availableWidthPx = with(density) { maxWidth.toPx() } - dividerWidthPx
        val clampedFraction = leftFraction.coerceIn(minLeftFraction, maxLeftFraction)
        if (clampedFraction != leftFraction) {
            leftFraction = clampedFraction
        }
        val leftWidth = with(density) { (availableWidthPx * clampedFraction).toDp() }

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(leftWidth)
                    .fillMaxHeight()
            ) {
                leftContent()
            }

            SplitPaneDivider(
                width = dividerWidth,
                onDrag = { deltaPx ->
                    if (availableWidthPx > 0f) {
                        leftFraction = (leftFraction + deltaPx / availableWidthPx)
                            .coerceIn(minLeftFraction, maxLeftFraction)
                    }
                },
                modifier = Modifier.fillMaxHeight()
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                rightContent()
            }
        }
    }
}

@Composable
private fun SplitPaneDivider(
    width: Dp,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(width)
            .testTag("HorizontalSplitPaneDivider")
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { _, dragAmount -> onDrag(dragAmount) }
                )
            }
            .background(
                if (isDragging) BrandRed.copy(alpha = 0.35f) else TableHeaderBg.copy(alpha = 0.45f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(28.dp)
                .background(if (isDragging) BrandRed else TableHeaderBg)
        )
    }
}
