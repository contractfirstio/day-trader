package daytrader.replay

import daytrader.data.persistence.AppDataFiles
import daytrader.domain.RthMarketSessions
import daytrader.e2e.E2EReplayTest
import daytrader.replay.support.ReplaySessionFixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@E2EReplayTest
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
            assertEquals(1_780_579_800_000L, entries.single().sessionStartedEpochMs)
            assertNotNull(entries.single().sessionStartedAtLabel)
            assertNotNull(entries.single().captureSummary)
            assertEquals(0.0, entries.single().captureSummary!!.pnl)
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

    @Test
    fun filterBySymbol_matchesCaseInsensitively() {
        val entries = listOf(
            entry(symbol = "AAPL", sessionDate = "2026-06-04"),
            entry(symbol = "TSCO", sessionDate = "2026-06-10"),
            entry(symbol = "MSFT", sessionDate = "2026-06-05")
        )
        assertEquals(1, SessionReplayCatalog.filterBySymbol(entries, "tsco").size)
        assertEquals("TSCO", SessionReplayCatalog.filterBySymbol(entries, "tsco").single().symbol)
        assertEquals(entries, SessionReplayCatalog.filterBySymbol(entries, ""))
        assertEquals(entries, SessionReplayCatalog.filterBySymbol(entries, "   "))
    }

    @Test
    fun filterBySymbol_matchesCompanyNameAndWildcards() {
        val entries = listOf(
            entry(symbol = "META", companyName = "Meta Platforms Inc.", sessionDate = "2026-06-04"),
            entry(symbol = "AAPL", companyName = "Apple Inc.", sessionDate = "2026-06-05")
        )
        assertEquals(1, SessionReplayCatalog.filterBySymbol(entries, "meta").size)
        assertEquals("META", SessionReplayCatalog.filterBySymbol(entries, "meta").single().symbol)
        assertEquals(1, SessionReplayCatalog.filterBySymbol(entries, "*platform*").size)
        assertEquals(1, SessionReplayCatalog.filterBySymbol(entries, "M*A").size)
        assertEquals(1, SessionReplayCatalog.filterBySymbol(entries, "apple").size)
    }

    @Test
    fun distinctSymbols_returnsSortedUniqueSymbols() {
        val entries = listOf(
            entry(symbol = "TSCO"),
            entry(symbol = "AAPL"),
            entry(symbol = "aapl"),
            entry(symbol = null)
        )
        assertEquals(listOf("AAPL", "TSCO"), SessionReplayCatalog.distinctSymbols(entries))
    }

    @Test
    fun filterByStartedAt_matchesDateTimeAndSessionDate() {
        val juneFourth = 1_780_579_800_000L
        val juneTenth = 1_781_126_544_262L
        val entries = listOf(
            entry(symbol = "AAPL", sessionDate = "2026-06-04", sessionStartedEpochMs = juneFourth),
            entry(symbol = "TSCO", sessionDate = "2026-06-10", sessionStartedEpochMs = juneTenth)
        )
        assertEquals(1, SessionReplayCatalog.filterByStartedAt(entries, "2026-06-10").size)
        assertEquals("TSCO", SessionReplayCatalog.filterByStartedAt(entries, "2026-06-10").single().symbol)
        assertEquals(2, SessionReplayCatalog.filter(entries, "", "2026-06").size)
        assertEquals(1, SessionReplayCatalog.filter(entries, "AAPL", "2026-06-04").size)
    }

    @Test
    fun distinctSessionDates_returnsSortedDescendingUniqueDates() {
        val entries = listOf(
            entry(sessionDate = "2026-06-04"),
            entry(sessionDate = "2026-06-10"),
            entry(sessionDate = "2026-06-04", sessionStartedEpochMs = 1_781_126_544_262L)
        )
        assertEquals(listOf("2026-06-10", "2026-06-04"), SessionReplayCatalog.distinctSessionDates(entries))
    }

    @Test
    fun filterByStartedAt_exactIsoDate_matchesSessionDateOnly() {
        val entries = listOf(
            entry(sessionDate = "2026-06-04"),
            entry(sessionDate = "2026-06-10", symbol = "MSFT")
        )
        assertEquals(1, SessionReplayCatalog.filterByStartedAt(entries, "2026-06-04").size)
        assertEquals(0, SessionReplayCatalog.filterByStartedAt(entries, "2026-06-05").size)
    }

    @Test
    fun discoverUnderScope_usesDeploymentMarketZoneId() {
        val root = Files.createTempDirectory("replay-catalog-zone")
        try {
            val scopeRoot = root.resolve("paper-live-ib")
            val sessionDir = scopeRoot.resolve("sessions/dep-lse/sess-1")
            Files.createDirectories(sessionDir)
            val contents = hybridContents()
            Files.writeString(sessionDir.resolve("manifest.json"), contents.manifestJson!!)
            Files.writeString(sessionDir.resolve("application.jsonl"), contents.applicationJsonl)
            Files.writeString(sessionDir.resolve("historical.jsonl"), contents.historicalJsonl)
            Files.writeString(
                scopeRoot.resolve("deployments.json"),
                """
                {
                  "deployments": [{
                    "id": "dep-lse",
                    "strategy": "TOUCH_AND_TURN_SCALPER",
                    "status": "STOPPED",
                    "configuration": {
                      "symbol": "RR.",
                      "maxAtRisk": 500,
                      "marketZoneId": "Europe/London",
                      "currencyCode": "GBP"
                    },
                    "live": {
                      "state": "IDLE",
                      "side": "LONG"
                    }
                  }]
                }
                """.trimIndent()
            )

            val entries = SessionReplayCatalog.discoverUnderScope(scopeRoot, "paper-live-ib")
            assertEquals(1, entries.size)
            assertEquals(RthMarketSessions.EUR.zoneId, entries.single().marketZoneId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun filterByMarket_matchesZoneId() {
        val entries = listOf(
            entry(symbol = "AAPL", marketZoneId = RthMarketSessions.US.zoneId),
            entry(symbol = "TSCO", marketZoneId = RthMarketSessions.EUR.zoneId, sessionDate = "2026-06-10"),
            entry(symbol = "700", marketZoneId = RthMarketSessions.HK.zoneId, sessionDate = "2026-06-11")
        )
        assertEquals(1, SessionReplayCatalog.filterByMarket(entries, RthMarketSessions.US.zoneId).size)
        assertEquals(
            "TSCO",
            SessionReplayCatalog.filterByMarket(entries, RthMarketSessions.EUR.zoneId).single().symbol
        )
        assertEquals(entries, SessionReplayCatalog.filterByMarket(entries, null))
    }

    @Test
    fun filterByMarket_fallsBackToSymbolWhenMarketZoneIdMissing() {
        val entries = listOf(
            entry(symbol = "0700", marketZoneId = null, sessionDate = "2026-06-18"),
            entry(symbol = "AAPL", marketZoneId = null, sessionDate = "2026-06-18")
        )
        assertEquals(1, SessionReplayCatalog.filterByMarket(entries, RthMarketSessions.HK.zoneId).size)
        assertEquals("0700", SessionReplayCatalog.filterByMarket(entries, RthMarketSessions.HK.zoneId).single().symbol)
    }

    @Test
    fun filter_combinesMarketDateAndSymbol() {
        val entries = listOf(
            entry(symbol = "AAPL", marketZoneId = RthMarketSessions.US.zoneId, sessionDate = "2026-06-04"),
            entry(symbol = "MSFT", marketZoneId = RthMarketSessions.US.zoneId, sessionDate = "2026-06-10"),
            entry(symbol = "TSCO", marketZoneId = RthMarketSessions.EUR.zoneId, sessionDate = "2026-06-10")
        )
        val filtered = SessionReplayCatalog.filter(
            entries = entries,
            symbolQuery = "",
            startedAtQuery = "2026-06-10",
            marketZoneId = RthMarketSessions.US.zoneId
        )
        assertEquals(1, filtered.size)
        assertEquals("MSFT", filtered.single().symbol)
    }

    private fun entry(
        symbol: String? = "AAPL",
        companyName: String? = null,
        sessionDate: String = "2026-06-04",
        sessionStartedEpochMs: Long? = 1_780_579_800_000L,
        marketZoneId: String? = RthMarketSessions.US.zoneId
    ) = SessionReplayEntry(
        directoryPath = "/tmp/${symbol ?: "unknown"}-$sessionDate",
        brokerScope = "paper-live-ib",
        deploymentId = "dep-1",
        sessionId = "sess-1",
        symbol = symbol,
        companyName = companyName,
        sessionDate = sessionDate,
        sessionStartedEpochMs = sessionStartedEpochMs,
        marketZoneId = marketZoneId,
        label = "$symbol · $sessionDate · sess-1 (paper-live-ib)"
    )

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
