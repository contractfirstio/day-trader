package daytrader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.broker.emulator.EmulatorSymbolLookup
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.presentation.Formatters
import daytrader.replay.SessionBundleDirectoryReader
import daytrader.replay.SessionReplayCatalog
import daytrader.replay.SessionReplayCaptureSummary
import daytrader.replay.SessionReplayEntry
import daytrader.replay.toReplayCaptureSummary
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionReplayPickerScreen(
    entries: List<SessionReplayEntry>,
    onBrowseFolder: () -> String?,
    onContinue: (SessionReplayEntry?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<SessionReplayEntry?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var symbolFilter by remember { mutableStateOf("") }
    var startedAtFilter by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val sessionDateOptions = remember(entries) { SessionReplayCatalog.distinctSessionDates(entries) }
    val filteredEntries = remember(entries, symbolFilter, startedAtFilter) {
        SessionReplayCatalog.filter(entries, symbolFilter, startedAtFilter)
    }
    val hasActiveFilter = symbolFilter.isNotBlank() || startedAtFilter.isNotBlank()

    SideEffect {
        val current = selected ?: return@SideEffect
        val isCatalogEntry = entries.any { it.directoryPath == current.directoryPath }
        if (isCatalogEntry && filteredEntries.none { it.directoryPath == current.directoryPath }) {
            selected = null
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = DarkBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Session Replay",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pick an initial capture or start replay — the correct capture loads automatically " +
                        "when you start each deployment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    modifier = Modifier.widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            text = "No captured sessions found under Application Support/Day Trader.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        OutlinedTextField(
                            value = symbolFilter,
                            onValueChange = { symbolFilter = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search symbol or name") },
                            placeholder = { Text("e.g. meta, AAPL, M*A") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            colors = replayFilterFieldColors(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = startedAtFilter,
                            onValueChange = { startedAtFilter = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Filter by start date/time") },
                            placeholder = { Text("e.g. 2026-06-10 or 09:30") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            colors = replayFilterFieldColors(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        if (sessionDateOptions.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                sessionDateOptions.forEach { sessionDate ->
                                    FilterChip(
                                        selected = startedAtFilter == sessionDate,
                                        onClick = {
                                            startedAtFilter = if (startedAtFilter == sessionDate) "" else sessionDate
                                        },
                                        label = { Text(sessionDate) },
                                        colors = replayFilterChipColors()
                                    )
                                }
                            }
                        }
                        if (hasActiveFilter) {
                            Text(
                                text = "${filteredEntries.size} of ${entries.size} sessions",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (filteredEntries.isEmpty()) {
                            Text(
                                text = buildString {
                                    append("No sessions match")
                                    if (symbolFilter.isNotBlank()) append(" search \"$symbolFilter\"")
                                    if (symbolFilter.isNotBlank() && startedAtFilter.isNotBlank()) append(" and")
                                    if (startedAtFilter.isNotBlank()) append(" start time \"$startedAtFilter\"")
                                    append('.')
                                },
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            filteredEntries.forEach { entry ->
                                SessionReplayEntryCard(
                                    entry = entry,
                                    selected = selected?.directoryPath == entry.directoryPath,
                                    onClick = { selected = entry }
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            browseError = null
                            val path = onBrowseFolder() ?: return@OutlinedButton
                            SessionBundleDirectoryReader.loadReplayableFromDirectory(path)
                                .fold(
                                    onSuccess = { bundle ->
                                        selected = SessionReplayCatalog.entryFromDirectory(path)
                                            ?: SessionReplayEntry(
                                                directoryPath = path,
                                                brokerScope = "custom",
                                                deploymentId = bundle.deploymentId,
                                                sessionId = bundle.sessionId,
                                                symbol = bundle.symbol,
                                                companyName = EmulatorSymbolLookup.companyName(bundle.symbol),
                                                sessionDate = bundle.sessionDate,
                                                sessionStartedEpochMs = bundle.timeline.sessionStartedEpochMs,
                                                label = buildString {
                                                    append(bundle.symbol)
                                                    bundle.sessionDate?.let { append(" · ").append(it) }
                                                    append(" · ")
                                                        .append(
                                                            SessionReplayCatalog.formatSessionStartedAt(
                                                                bundle.timeline.sessionStartedEpochMs
                                                            )
                                                        )
                                                    append(" · ").append(bundle.sessionId)
                                                    append(" (custom)")
                                                },
                                                captureSummary = bundle.toReplayCaptureSummary()
                                            )
                                    },
                                    onFailure = { error ->
                                        browseError = error.message
                                            ?: "Selected folder is not a replayable session capture"
                                    }
                                )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Browse for session folder…")
                    }
                    browseError?.let { error ->
                        Text(text = error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider(color = SurfaceDark, modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = onBack, shape = RoundedCornerShape(10.dp)) {
                    Text("Back")
                }
                Button(
                    onClick = { onContinue(selected) },
                    enabled = entries.isNotEmpty(),
                    modifier = Modifier
                        .widthIn(min = 220.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GainGreen,
                        contentColor = Color.Black,
                        disabledContainerColor = Color(0xFF3A3D48),
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Text("Start Replay", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SessionReplayEntryCard(
    entry: SessionReplayEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    val resultStyle = replayResultStyle(entry.captureSummary)
    val borderColor = when {
        selected -> GainGreen
        resultStyle != null -> resultStyle.borderColor
        else -> Color(0xFF4A4E5C)
    }
    val containerColor = when {
        selected -> Color(0xFF152218)
        resultStyle != null -> resultStyle.containerColor
        else -> Color(0xFF1C1D24)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.symbol ?: entry.deploymentId,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                entry.companyName?.let { name ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = name,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                entry.sessionStartedAtLabel?.let { startedAt ->
                    Text(
                        text = "Started $startedAt",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = buildString {
                        entry.sessionDate?.let { append(it).append(" · ") }
                        append(entry.sessionId)
                        append(" · ")
                        append(entry.brokerScope)
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (entry.symbol == null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Deployment ${entry.deploymentId}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            SessionReplayResultBadge(
                summary = entry.captureSummary,
                resultStyle = resultStyle
            )
        }
    }
}

@Composable
private fun SessionReplayResultBadge(
    summary: SessionReplayCaptureSummary?,
    resultStyle: ReplayResultStyle?
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = formatReplayCapturePnl(summary),
            color = resultStyle?.pnlColor ?: TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        summary?.let {
            Text(
                text = replayOutcomeLabel(it),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private data class ReplayResultStyle(
    val pnlColor: Color,
    val borderColor: Color,
    val containerColor: Color
)

private fun replayResultStyle(summary: SessionReplayCaptureSummary?): ReplayResultStyle? {
    if (summary == null) return null
    val formatted = formatReplayCapturePnl(summary)
    return when {
        formatted == Formatters.FLAT_PNL_LABEL -> ReplayResultStyle(
            pnlColor = TextSecondary,
            borderColor = Color(0xFF4A4E5C),
            containerColor = Color(0xFF1C1D24)
        )
        summary.pnl > 0.005 -> ReplayResultStyle(
            pnlColor = GainGreen,
            borderColor = GainGreen.copy(alpha = 0.55f),
            containerColor = Color(0xFF142218)
        )
        summary.pnl < -0.005 -> ReplayResultStyle(
            pnlColor = LossRed,
            borderColor = LossRed.copy(alpha = 0.55f),
            containerColor = Color(0xFF221418)
        )
        else -> ReplayResultStyle(
            pnlColor = TextSecondary,
            borderColor = Color(0xFF4A4E5C),
            containerColor = Color(0xFF1C1D24)
        )
    }
}

private fun formatReplayCapturePnl(summary: SessionReplayCaptureSummary?): String {
    if (summary == null) return "—"
    val hasPnL = kotlin.math.abs(summary.pnl) >= 0.01
    return if (hasPnL) {
        Formatters.money(summary.pnl, summary.currencyCode, showSign = true)
    } else {
        Formatters.FLAT_PNL_LABEL
    }
}

private fun replayOutcomeLabel(summary: SessionReplayCaptureSummary): String =
    when (summary.outcome) {
        TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED ->
            if (summary.positionOpened) "Trade" else "Trade submitted"
        else -> "No trade"
    }

@Composable
private fun replayFilterFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = TableHeaderBg,
    unfocusedContainerColor = TableHeaderBg,
    focusedBorderColor = GainGreen,
    unfocusedBorderColor = SurfaceDark,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary
)

@Composable
private fun replayFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color(0xFF152218),
    selectedLabelColor = GainGreen,
    containerColor = TableHeaderBg,
    labelColor = TextSecondary
)
