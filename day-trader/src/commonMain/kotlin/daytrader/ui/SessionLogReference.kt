package daytrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val folder = SessionLogUi.logFolderRelativePath(deploymentId, sessionId)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("SessionLogReference-$sessionId")
    ) {
        Text(
            text = "Session ID: $sessionId",
            fontSize = if (compact) 10.sp else 11.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("SessionIdLabel-$sessionId")
        )
        if (!compact) {
            Text(
                text = SessionLogUi.logFolderLabel(deploymentId, sessionId),
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .testTag("SessionLogFolderLabel-$sessionId")
            )
        } else {
            Text(
                text = folder,
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .testTag("SessionLogFolderLabel-$sessionId")
            )
        }
    }
}
