package daytrader.replay

import daytrader.data.persistence.AppDataFiles
import daytrader.replay.support.ReplaySessionFixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReplayCatalogTest {

    @Test
    fun discoverUnderScope_findsHybridSessionWithManifestAndApplicationLog() {
        val root = Files.createTempDirectory("replay-catalog")
        try {
            val sessionDir = root.resolve("paper-live-ib/sessions/dep-1/sess-1")
            Files.createDirectories(sessionDir)
            val contents = hybridContents()
            Files.writeString(sessionDir.resolve("manifest.json"), contents.manifestJson!!)
            Files.writeString(sessionDir.resolve("application.jsonl"), contents.applicationJsonl)
            Files.writeString(sessionDir.resolve("historical.jsonl"), contents.historicalJsonl)

            val entries = SessionReplayCatalog.discoverUnderScope(
                root.resolve("paper-live-ib"),
                "paper-live-ib"
            )
            assertEquals(1, entries.size)
            assertEquals("AAPL", entries.single().symbol)
            assertEquals("2026-06-04", entries.single().sessionDate)
            assertTrue(entries.single().directoryPath.endsWith("${AppDataFiles.SESSIONS_DIR}/dep-1/sess-1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun discoverUnderScope_excludesOfflineEmulatorSessions() {
        val root = Files.createTempDirectory("replay-catalog-emulator")
        try {
            val sessionDir = root.resolve("emulator/sessions/dep-1/sess-1")
            Files.createDirectories(sessionDir)
            Files.writeString(sessionDir.resolve("manifest.json"), ReplaySessionFixtures.minimalContents().manifestJson!!)
            Files.writeString(
                sessionDir.resolve("application.jsonl"),
                ReplaySessionFixtures.minimalContents().applicationJsonl
            )

            val entries = SessionReplayCatalog.discoverUnderScope(root.resolve("emulator"), "emulator")
            assertEquals(0, entries.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun entryFromDirectory_loadsHybridCustomPath() {
        val root = Files.createTempDirectory("replay-entry")
        try {
            val sessionDir = root.resolve("custom-session")
            Files.createDirectories(sessionDir)
            val contents = hybridContents()
            Files.writeString(sessionDir.resolve("manifest.json"), contents.manifestJson!!)
            Files.writeString(sessionDir.resolve("application.jsonl"), contents.applicationJsonl)
            val entry = SessionReplayCatalog.entryFromDirectory(sessionDir.toString())
            assertNotNull(entry)
            assertEquals("AAPL", entry.symbol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun entryFromDirectory_excludesOfflineEmulatorCapture() {
        val root = Files.createTempDirectory("replay-entry-emulator")
        try {
            val sessionDir = root.resolve("custom-session")
            Files.createDirectories(sessionDir)
            Files.writeString(sessionDir.resolve("manifest.json"), ReplaySessionFixtures.minimalContents().manifestJson!!)
            Files.writeString(
                sessionDir.resolve("application.jsonl"),
                ReplaySessionFixtures.minimalContents().applicationJsonl
            )
            assertNull(SessionReplayCatalog.entryFromDirectory(sessionDir.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun hybridContents() = ReplaySessionFixtures.minimalContents().let { contents ->
        contents.copy(
            manifestJson = contents.manifestJson?.replace(
                "\"brokerKind\": \"EMULATOR\"",
                "\"brokerKind\": \"EMULATOR_LIVE_IB_MARKET_DATA\""
            ),
            applicationJsonl = contents.applicationJsonl
                .replace("\"brokerKind\":\"EMULATOR\"", "\"brokerKind\":\"EMULATOR_LIVE_IB_MARKET_DATA\"")
        )
    }
}
