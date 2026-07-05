package daytrader.replay

import daytrader.e2e.E2EReplayTest
import daytrader.domain.RthMarketSessions
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Optional probe against the local Day Trader data directory (ignored when absent).
 */
@E2EReplayTest
class SessionReplayCatalogLiveDataProbeTest {

    @Test
    fun filter_hkJune18_matchesLocalCapturesIfPresent() {
        if (System.getenv("DAY_TRADER_LIVE_DATA_PROBE") != "true") return
        val base = Path.of(System.getProperty("user.home"), "Library/Application Support/Day Trader")
        if (!Files.isDirectory(base)) return

        val entries = SessionReplayCatalog.discover(base.toString())
        if (entries.isEmpty()) return

        val hkJune18 = SessionReplayCatalog.filter(
            entries = entries,
            symbolQuery = "",
            startedAtQuery = "2026-06-18",
            marketZoneId = RthMarketSessions.HK.zoneId
        )
        val june18Entries = entries.filter { it.sessionDate == "2026-06-18" }
        val nullSessionDate = june18Entries.count { it.sessionDate == null }
        val nullZone = june18Entries.count { it.marketZoneId == null }
        println(
            "catalog=${entries.size} hkJune18Filtered=${hkJune18.size} " +
                "june18=${june18Entries.size} nullSessionDate=$nullSessionDate nullZone=$nullZone"
        )
        if (june18Entries.isNotEmpty()) {
            assertTrue(
                hkJune18.isNotEmpty(),
                "Expected HK filter on 2026-06-18 to match ${june18Entries.size} captures but got ${hkJune18.size}. " +
                    "Sample: sessionDate=${june18Entries.first().sessionDate} " +
                    "marketZoneId=${june18Entries.first().marketZoneId}"
            )
        }
    }
}
