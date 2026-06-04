package daytrader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import daytrader.replay.SessionReplayEntry
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun SessionReplayPickerScreen(
    entries: List<SessionReplayEntry>,
    onBrowseFolder: () -> String?,
    onContinue: (SessionReplayEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<SessionReplayEntry?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

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
                    text = "Choose a captured session folder to replay against recorded IB data.",
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
                        entries.forEach { entry ->
                            SessionReplayEntryCard(
                                entry = entry,
                                selected = selected?.directoryPath == entry.directoryPath,
                                onClick = { selected = entry }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            browseError = null
                            val path = onBrowseFolder() ?: return@OutlinedButton
                            val entry = daytrader.replay.SessionReplayCatalog.entryFromDirectory(path)
                            if (entry == null) {
                                browseError = "Selected folder is missing application.jsonl or manifest.json"
                            } else {
                                selected = entry
                            }
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
                    onClick = { selected?.let(onContinue) },
                    enabled = selected != null,
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
    val borderColor = if (selected) GainGreen else Color(0xFF4A4E5C)
    val containerColor = if (selected) Color(0xFF152218) else Color(0xFF1C1D24)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = entry.label, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.directoryPath,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
