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
import androidx.compose.material3.LinearProgressIndicator
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
import daytrader.presentation.Formatters
import daytrader.replay.BatchReplayProgress
import daytrader.replay.BatchReplayRunner
import daytrader.replay.BatchReplaySummary
import daytrader.replay.ReplayCaptureRef
import daytrader.replay.ReplayCatalogTargets
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
    batchReplayRunner: BatchReplayRunner?,
    replayCaptureCatalog: List<ReplayCaptureRef>,
    replaySeedDirectoryPaths: List<String>,
    loadReplayBundle: (String) -> Result<SessionBundle>,
    replaySettingsRepository: ReplaySettingsRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val playbackState by controller.runtime.playbackOrchestrator.state.collectAsState()
    val replaySettings by replaySettingsRepository.settings.collectAsState()
    val selectedSpeed = ReplayQuoteSpeed.closest(replaySettings.quoteIntervalMs)
    val batchProgress by (batchReplayRunner?.progress ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(BatchReplayProgress())
    }).collectAsState()
    val running = batchProgress.running
    val targets = remember(replayCaptureCatalog, replaySeedDirectoryPaths) {
        ReplayCatalogTargets.resolve(replayCaptureCatalog, replaySeedDirectoryPaths, loadReplayBundle)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Replay backtest · ${targets.size} capture(s) in list",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Bootstrap: ${bundle.symbol} · ${bundle.sessionDate.orEmpty()} — " +
                    "uses each deployment's current rules",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            if (running) {
                LinearProgressIndicator(
                    progress = {
                        if (batchProgress.total <= 0) 0f
                        else batchProgress.finishedCount.toFloat() / batchProgress.total.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = GainGreen,
                    trackColor = Color(0xFF3A3D48)
                )
                Text(
                    text = batchProgressLabel(batchProgress),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                Text(
                    text = playbackStatusLabel(playbackState, replaySettings),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                batchProgress.summary?.let { summary ->
                    Text(
                        text = batchSummaryLabel(summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summary.totalPnl >= 0.0) GainGreen else LossRed
                    )
                }
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
                    if (running || batchReplayRunner == null) return@Button
                    scope.launch {
                        batchReplayRunner.runCatalog(targets)
                    }
                },
                enabled = !running && batchReplayRunner != null && targets.isNotEmpty(),
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

private fun batchProgressLabel(progress: BatchReplayProgress): String {
    val current = progress.currentSymbol?.let { " · $it" }.orEmpty()
    return "${progress.finishedCount} / ${progress.total} sessions with results" +
        " (${progress.completed} ok, ${progress.failed} failed)$current"
}

private fun batchSummaryLabel(summary: BatchReplaySummary): String {
    val pnl = Formatters.currency(summary.totalPnl, showSign = true)
    val original = Formatters.currency(summary.originalTotalPnl, showSign = true)
    val delta = Formatters.currency(summary.totalPnlDelta, showSign = true)
    val traded = summary.wins + summary.losses
    val winPct = if (traded > 0) (summary.wins * 100 / traded) else 0
    return buildString {
        append("Done: ${summary.tangibleResults} sessions · replay P&L $pnl")
        append(" · was $original · Δ $delta")
        if (traded > 0) append(" · win $winPct% ($traded round-trips)")
        append(" · ${summary.noTrades} no-trade")
        if (summary.groundTruthFillSessions > 0) {
            append(" · ${summary.groundTruthFillSessions} exact-fill")
        }
        if (summary.failed > 0) append(" · ${summary.failed} failed")
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
        ReplayPlaybackState.Idle ->
            "Interactive Start uses quote speed $intervalLabel$turboLabel · Run Replay is headless batch"
        is ReplayPlaybackState.FastForming ->
            "Opening bar fast-forward (${state.step}/${state.totalSteps})$turboLabel"
        ReplayPlaybackState.AwaitingClosedBar -> "Loading closed candle from capture…$turboLabel"
        is ReplayPlaybackState.DrippingQuotes ->
            "Quotes ${state.published}/${state.total} · $intervalLabel$turboLabel"
    }
}
