package daytrader.diagnostics

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Unified wall-clock stamp for correlating console and JSONL logs across files.
 */
data class LogTimestamp(
    /** ISO local date-time, e.g. `2026-05-29T14:30:45.123` */
    val at: String,
    /** Epoch milliseconds (UTC) for precise cross-log alignment. */
    val epochMs: Long
)

object LogTimestamps {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    fun now(clock: Clock = Clock.systemDefaultZone()): LogTimestamp {
        val instant = clock.instant()
        val at = LocalDateTime.ofInstant(instant, clock.zone).format(formatter)
        return LogTimestamp(at = at, epochMs = instant.toEpochMilli())
    }
}
