package daytrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Shared 1 Hz tick for countdown labels — one coroutine per screen tree. */
val LocalUiSecondTick = compositionLocalOf { 0 }

@Composable
fun UiSecondTickProvider(content: @Composable () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    CompositionLocalProvider(LocalUiSecondTick provides tick) {
        content()
    }
}
