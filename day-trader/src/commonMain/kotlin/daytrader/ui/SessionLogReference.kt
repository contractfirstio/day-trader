package daytrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.strategies.SessionLogUi
import daytrader.ui.theme.TextSecondary

@Composable
fun SessionLogReference(
    deploymentId: String,
    sessionId: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val clipboardManager = LocalClipboardManager.current
    val copyText = SessionLogUi.clipboardText(deploymentId, sessionId)
    val folderLabel = if (compact) {
        SessionLogUi.logFolderAbsolutePath(deploymentId, sessionId) + "/"
    } else {
        SessionLogUi.logFolderLabel(deploymentId, sessionId)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("SessionLogReference-$sessionId"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Session ID: $sessionId",
                fontSize = if (compact) 10.sp else 11.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("SessionIdLabel-$sessionId")
            )
            Text(
                text = folderLabel,
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.85f),
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .testTag("SessionLogFolderLabel-$sessionId")
            )
        }
        TextButton(
            onClick = { clipboardManager.setText(AnnotatedString(copyText)) },
            modifier = Modifier.testTag("SessionLogCopy-$sessionId"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                text = if (compact) "Copy" else "Copy prompt",
                fontSize = if (compact) 10.sp else 11.sp,
                color = TextSecondary
            )
        }
    }
}
