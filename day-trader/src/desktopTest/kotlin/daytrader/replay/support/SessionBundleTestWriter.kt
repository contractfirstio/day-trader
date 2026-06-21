package daytrader.replay.support

import daytrader.replay.SessionBundleContents
import java.nio.file.Files
import java.nio.file.Path

/** Writes on-disk session capture directories for desktop replay integration tests. */
object SessionBundleTestWriter {
    fun writeSessionDirectory(
        scopeRoot: Path,
        deploymentId: String,
        sessionId: String,
        contents: SessionBundleContents,
    ): Path {
        val sessionDir = scopeRoot.resolve("sessions/$deploymentId/$sessionId")
        Files.createDirectories(sessionDir)
        contents.manifestJson?.let { Files.writeString(sessionDir.resolve("manifest.json"), it) }
        Files.writeString(sessionDir.resolve("application.jsonl"), contents.applicationJsonl)
        Files.writeString(sessionDir.resolve("historical.jsonl"), contents.historicalJsonl)
        Files.writeString(sessionDir.resolve("prices.jsonl"), contents.pricesJsonl)
        return sessionDir
    }
}
