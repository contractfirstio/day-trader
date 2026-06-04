package daytrader.replay

import daytrader.data.persistence.AppDataFiles
import daytrader.replay.support.ReplaySessionFixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionReplayCatalogTest {

    @Test
    fun discoverUnderScope_findsSessionWithManifestAndApplicationLog() {
        val root = Files.createTempDirectory("replay-catalog")
        try {
            val sessionDir = root.resolve("emulator/sessions/dep-1/sess-1")
            Files.createDirectories(sessionDir)
            Files.writeString(sessionDir.resolve("manifest.json"), ReplaySessionFixtures.minimalContents().manifestJson!!)
            Files.writeString(
                sessionDir.resolve("application.jsonl"),
                ReplaySessionFixtures.minimalContents().applicationJsonl
            )
            Files.writeString(
                sessionDir.resolve("historical.jsonl"),
                ReplaySessionFixtures.minimalContents().historicalJsonl
            )

            val entries = SessionReplayCatalog.discoverUnderScope(root.resolve("emulator"), "emulator")
            assertEquals(1, entries.size)
            assertEquals("AAPL", entries.single().symbol)
            assertEquals("2026-06-04", entries.single().sessionDate)
            assertTrue(entries.single().directoryPath.endsWith("${AppDataFiles.SESSIONS_DIR}/dep-1/sess-1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun entryFromDirectory_loadsCustomPath() {
        val root = Files.createTempDirectory("replay-entry")
        try {
            val sessionDir = root.resolve("custom-session")
            Files.createDirectories(sessionDir)
            Files.writeString(sessionDir.resolve("manifest.json"), ReplaySessionFixtures.minimalContents().manifestJson!!)
            Files.writeString(
                sessionDir.resolve("application.jsonl"),
                ReplaySessionFixtures.minimalContents().applicationJsonl
            )
            val entry = SessionReplayCatalog.entryFromDirectory(sessionDir.toString())
            assertNotNull(entry)
            assertEquals("AAPL", entry.symbol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
