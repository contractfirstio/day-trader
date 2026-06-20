package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import daytrader.data.ReplaySettingsRepository
import daytrader.replay.ReplayComparison
import daytrader.replay.ReplayFillComparison
import daytrader.replay.ReplayPlaybackState
import daytrader.replay.ReplayQuoteSpeed
import daytrader.replay.ReplaySessionController
import daytrader.replay.ReplaySettings
import daytrader.replay.SessionBundle
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ReplayControlBar(
    bundle: SessionBundle,
    controller: ReplaySessionController,
    replaySettingsRepository: ReplaySettingsRepository,
    modifier: Modifier = Modifier
) {
    var running by remember { mutableStateOf(false) }
    var comparison by remember { mutableStateOf<ReplayComparison?>(controller.lastComparison) }
    var fillComparison by remember { mutableStateOf<ReplayFillComparison?>(controller.lastFillComparison) }
    val scope = rememberCoroutineScope()
    val playbackState by controller.runtime.playbackOrchestrator.state.collectAsState()
    val replaySettings by replaySettingsRepository.settings.collectAsState()
    val selectedSpeed = ReplayQuoteSpeed.closest(replaySettings.quoteIntervalMs)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Replay: ${bundle.symbol} · ${bundle.sessionDate.orEmpty()}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Captured session ${bundle.sessionId} — virtual time, no IB connection required",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = playbackStatusLabel(playbackState, replaySettings),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            comparison?.let { result ->
                val color = if (result.passed) GainGreen else LossRed
                Text(
                    text = buildString {
                        append("Outcome: ")
                        append(if (result.outcomeMatches) "match" else "mismatch")
                        append(" (expected=${result.expectedOutcome}, actual=${result.actualOutcome})")
                        fillComparison?.let { fills ->
                            append(" · fills ")
                            append(if (fills.passed) "match" else "mismatch")
                            append(" (${fills.actualFillCount}/${fills.expectedFillCount})")
                        }
                    },
                    color = color,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReplayQuoteSpeedPicker(
                selected = selectedSpeed,
                enabled = !running,
                onSelect = { speed ->
                    replaySettingsRepository.update { settings ->
                        settings.copy(quoteIntervalMs = speed.intervalMs)
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = replaySettings.turboDuringPlayback,
                    onCheckedChange = { enabled ->
                        replaySettingsRepository.update { settings ->
                            settings.copy(turboDuringPlayback = enabled)
                        }
                    },
                    enabled = !running,
                )
                Text(
                    "Turbo (skip quote UI)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    scope.launch {
                        runCatching { controller.runReplay() }
                            .onSuccess {
                                comparison = it
                                fillComparison = controller.lastFillComparison
                            }
                            .onFailure {
                                comparison = null
                                fillComparison = null
                            }
                        running = false
                    }
                },
                enabled = !running,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GainGreen,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF3A3D48)
                )
            ) {
                Text(if (running) "Replaying…" else "Run Replay")
            }
        }
    }
}

@Composable
private fun ReplayQuoteSpeedPicker(
    selected: ReplayQuoteSpeed,
    enabled: Boolean,
    onSelect: (ReplayQuoteSpeed) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Speed: ${selected.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReplayQuoteSpeed.entries.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(speed.label) },
                    onClick = {
                        expanded = false
                        onSelect(speed)
                    }
                )
            }
        }
    }
}

private fun playbackStatusLabel(state: ReplayPlaybackState, settings: ReplaySettings): String {
    val intervalLabel = if (settings.quoteIntervalMs <= 0L) {
        "instant"
    } else {
        "${settings.quoteIntervalMs} ms"
    }
    val turboLabel = if (settings.turboDuringPlayback) " · turbo on" else ""
    return when (state) {
        ReplayPlaybackState.Idle -> "Playback idle — quote speed $intervalLabel$turboLabel"
        is ReplayPlaybackState.FastForming ->
            "Opening bar fast-forward (${state.step}/${state.totalSteps})$turboLabel"
        ReplayPlaybackState.AwaitingClosedBar -> "Loading closed candle from capture…$turboLabel"
        is ReplayPlaybackState.DrippingQuotes ->
            "Quotes ${state.published}/${state.total} · $intervalLabel$turboLabel"
    }
}
