package daytrader.diagnostics

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class LogTimestampsTest {
    @Test
    fun now_usesIsoLocalWithMillisAndEpoch() {
        val instant = Instant.parse("2026-05-29T18:30:45.678Z")
        val fixed = Clock.fixed(instant, ZoneOffset.UTC)
        val stamp = LogTimestamps.now(fixed)
        assertEquals("2026-05-29T18:30:45.678", stamp.at)
        assertEquals(instant.toEpochMilli(), stamp.epochMs)
    }
}
