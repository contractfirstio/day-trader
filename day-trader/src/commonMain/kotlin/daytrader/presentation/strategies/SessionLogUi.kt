package daytrader.presentation.strategies

import daytrader.data.persistence.AppDataFiles
import daytrader.platform.AppFileSystem

/** Relative log folder under the broker-scoped Application Support directory. */
object SessionLogUi {
    fun logFolderRelativePath(deploymentId: String, sessionId: String): String =
        "${AppDataFiles.sessionDirectory(deploymentId, sessionId)}/"

    fun logFolderAbsolutePath(deploymentId: String, sessionId: String): String =
        runCatching {
            AppFileSystem.dataFilePath(AppDataFiles.sessionDirectory(deploymentId, sessionId))
        }.getOrElse { logFolderRelativePath(deploymentId, sessionId) }

    fun logFolderLabel(deploymentId: String, sessionId: String): String =
        "Logs: ${logFolderAbsolutePath(deploymentId, sessionId)}/"

    fun applicationLogAbsolutePath(deploymentId: String, sessionId: String): String =
        runCatching {
            AppFileSystem.dataFilePath(AppDataFiles.sessionApplicationLogFileName(deploymentId, sessionId))
        }.getOrElse { AppDataFiles.sessionApplicationLogFileName(deploymentId, sessionId) }

    fun diagnosisPromptText(
        sessionId: String,
        broker: String,
        applicationLogPath: String,
    ): String = buildString {
        appendLine("Day Trader diagnosis — Session ID: $sessionId")
        appendLine("Broker: $broker")
        appendLine("Symptom: {one sentence}")
        appendLine()
        append(
            "Follow the Day Trader log diagnosis workflow: find $applicationLogPath, " +
                "correlate emulator/emulator/*.jsonl by epochMs+symbol if hybrid/emulator, " +
                "then explain with code. Cite log lines and epochMs."
        )
    }

    fun clipboardText(deploymentId: String, sessionId: String): String {
        val broker = runCatching { AppFileSystem.currentDataScope().dataDirectorySegment }
            .getOrElse { "unknown" }
        return diagnosisPromptText(
            sessionId = sessionId,
            broker = broker,
            applicationLogPath = applicationLogAbsolutePath(deploymentId, sessionId),
        )
    }
}
