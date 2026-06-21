package daytrader.replay

data class ReplaySettings(
    val quoteIntervalMs: Long = ReplayPlaybackConfig.DEFAULT_QUOTE_INTERVAL_MS,
    /** When true, skips chart sampling and quote-driven UI during active replay playback. */
    val turboDuringPlayback: Boolean = true,
) {
    init {
        require(quoteIntervalMs >= 0L) { "quoteIntervalMs must be non-negative" }
    }
}

enum class ReplayQuoteSpeed(val label: String, val intervalMs: Long) {
    /** No wall-clock pacing — virtual time advances as fast as the CPU processes each tick. */
    MAX_SPEED("Max speed", 0L),
    FAST("Fast (5 ms)", 5L),
    NORMAL("Normal (10 ms)", 10L),
    SLOW("Slow (50 ms)", 50L),
    VERY_SLOW("Very slow (200 ms)", 200L);

    val isMaxSpeed: Boolean
        get() = intervalMs <= 0L

    companion object {
        fun isMaxSpeed(intervalMs: Long): Boolean = intervalMs <= 0L

        fun closest(intervalMs: Long): ReplayQuoteSpeed =
            entries.minByOrNull { kotlin.math.abs(it.intervalMs - intervalMs) } ?: NORMAL
    }
}
