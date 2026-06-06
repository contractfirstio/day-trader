package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.watchlist.WatchlistPlanDiaryEditorUi
import daytrader.presentation.watchlist.WatchlistPlanDiaryEntryUi
import daytrader.ui.theme.*

@Composable
internal fun WatchlistPlanDiaryDialog(
    editor: WatchlistPlanDiaryEditorUi,
    onDismiss: () -> Unit,
    onStartAdd: () -> Unit,
    onStartEdit: (String) -> Unit,
    onCancelDraft: () -> Unit,
    onDraftBodyChange: (String) -> Unit,
    onDraftNotifyEnabledChange: (Boolean) -> Unit,
    onDraftNotifyDateChange: (String) -> Unit,
    onSaveEntry: () -> Unit,
    onDeleteEntry: (String) -> Unit,
    onDismissReminder: (String) -> Unit
) {
    val isEditing = editor.composingEntry || editor.editingEntryId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .testTag("WatchlistPlanDiaryDialog"),
        containerColor = SurfaceDark,
        title = {
            Column {
                Text(
                    "${editor.symbol} · ${editor.planLabel} — Diary",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(editor.companyName, color = TextSecondary, fontSize = 13.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isEditing) {
                    DiaryEntryDraftForm(
                        editor = editor,
                        onDraftBodyChange = onDraftBodyChange,
                        onDraftNotifyEnabledChange = onDraftNotifyEnabledChange,
                        onDraftNotifyDateChange = onDraftNotifyDateChange,
                        onCancelDraft = onCancelDraft,
                        onSaveEntry = onSaveEntry,
                        onDeleteEntry = onDeleteEntry
                    )
                } else {
                    OutlinedButton(
                        onClick = onStartAdd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("AddWatchlistPlanDiaryEntryButton")
                    ) {
                        Text("Add diary entry", color = Color.White)
                    }
                }

                if (editor.entries.isEmpty() && !isEditing) {
                    Text(
                        "No diary entries yet. Log notes, thesis updates, or reminders for this plan.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }

                editor.entries
                    .filter { it.id != editor.editingEntryId }
                    .forEach { entry ->
                    DiaryEntryCard(
                        entry = entry,
                        highlighted = entry.id == editor.focusedEntryId,
                        onEdit = { onStartEdit(entry.id) },
                        onDelete = { onDeleteEntry(entry.id) },
                        onDismissReminder = { onDismissReminder(entry.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun DiaryEntryDraftForm(
    editor: WatchlistPlanDiaryEditorUi,
    onDraftBodyChange: (String) -> Unit,
    onDraftNotifyEnabledChange: (Boolean) -> Unit,
    onDraftNotifyDateChange: (String) -> Unit,
    onCancelDraft: () -> Unit,
    onSaveEntry: () -> Unit,
    onDeleteEntry: (String) -> Unit
) {
    val canSave = editor.draftBody.trim().isNotEmpty() &&
        (!editor.draftNotifyEnabled || editor.draftNotifyOnDate.matches(Regex("""\d{4}-\d{2}-\d{2}""")))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (editor.editingEntryId != null) "Edit entry" else "New entry",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
        OutlinedTextField(
            value = editor.draftBody,
            onValueChange = onDraftBodyChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("WatchlistPlanDiaryBodyInput"),
            minLines = 4,
            placeholder = { Text("What happened? What are you watching for?", color = TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
                focusedBorderColor = TableHeaderBg,
                unfocusedBorderColor = TableHeaderBg,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Remind me on or after", color = Color.White, fontSize = 13.sp)
            Switch(
                checked = editor.draftNotifyEnabled,
                onCheckedChange = onDraftNotifyEnabledChange
            )
        }
        if (editor.draftNotifyEnabled) {
            ConfigField(
                label = "Notification date (YYYY-MM-DD)",
                value = editor.draftNotifyOnDate,
                onValueChange = onDraftNotifyDateChange
            )
            Text(
                "You'll be notified each time you open Day Trader on or after this date until you dismiss the reminder.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            if (editor.editingEntryId != null) {
                TextButton(
                    onClick = { onDeleteEntry(editor.editingEntryId) },
                    modifier = Modifier.testTag("DeleteWatchlistPlanDiaryEntry-${editor.editingEntryId}")
                ) {
                    Text("Delete entry", color = LossRed, fontSize = 13.sp)
                }
            }
            TextButton(onClick = onCancelDraft) {
                Text("Cancel", color = TextSecondary)
            }
            Button(
                onClick = onSaveEntry,
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("SaveWatchlistPlanDiaryEntryButton")
            ) {
                Text("Save entry", color = Color.White)
            }
        }
    }
}

@Composable
private fun DiaryEntryCard(
    entry: WatchlistPlanDiaryEntryUi,
    highlighted: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismissReminder: () -> Unit
) {
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val borderColor = when {
        highlighted && entry.reminderActive -> TradeBlueBorder
        highlighted -> TableHeaderBg
        entry.reminderActive -> TradeBlueBorder.copy(alpha = 0.7f)
        else -> TableHeaderBg
    }
    val borderWidth = if (highlighted || entry.reminderActive) 2.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, shape)
            .background(
                if (highlighted) TradeBlueSurface.copy(alpha = 0.45f) else DarkBackground,
                shape
            )
            .padding(12.dp)
            .testTag("WatchlistPlanDiaryEntry-${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(entry.formattedCreatedAt, color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!confirmDelete) {
                    TextButton(onClick = onEdit) {
                        Text("Edit", color = TextSecondary, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.testTag("DeleteWatchlistPlanDiaryEntry-${entry.id}")
                    ) {
                        Text("Delete", color = LossRed, fontSize = 12.sp)
                    }
                }
            }
        }
        if (confirmDelete) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Delete this diary entry?", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text("Cancel", color = TextSecondary, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("ConfirmDeleteWatchlistPlanDiaryEntry-${entry.id}")
                    ) {
                        Text("Delete", color = LossRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text(entry.body, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        entry.notifyOnDateLabel?.let { label ->
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
        if (entry.reminderActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Reminder active",
                    color = TradeBlueBorder,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = onDismissReminder,
                    modifier = Modifier.testTag("DismissWatchlistPlanDiaryReminder-${entry.id}")
                ) {
                    Text("Dismiss reminder", color = BrandRed, fontSize = 12.sp)
                }
            }
        }
    }
}
