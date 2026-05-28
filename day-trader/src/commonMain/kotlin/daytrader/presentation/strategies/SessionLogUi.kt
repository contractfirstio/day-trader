package daytrader.presentation.strategies

import daytrader.data.persistence.AppDataFiles

/** Relative log folder under the broker-scoped Application Support directory. */
object SessionLogUi {
    fun logFolderRelativePath(deploymentId: String, sessionId: String): String =
        "${AppDataFiles.sessionDirectory(deploymentId, sessionId)}/"

    fun logFolderLabel(deploymentId: String, sessionId: String): String =
        "Logs: ${logFolderRelativePath(deploymentId, sessionId)}"
}
