package daytrader.broker

/**
 * IB Gateway enforces ~50 outbound messages/sec (API error 100 when exceeded).
 * Defaults stay well below that; env overrides are capped to leave headroom.
 */
internal object IbRateLimits {
    const val IB_HARD_LIMIT_PER_SECOND = 50

    /** Default cap — ~half of IB hard limit. */
    const val DEFAULT_SAFE_MAX_PER_SECOND = 25

    /** Minimum spacing between consecutive outbound calls. */
    const val DEFAULT_MIN_INTERVAL_MS = 50L

    /** Minimum time between [reqExecutions] reloads. */
    const val DEFAULT_EXECUTIONS_REFRESH_MS = 5_000L

    fun maxMessagesPerSecond(): Int {
        val requested = System.getenv("DAY_TRADER_IB_MAX_MSG_PER_SEC")?.toIntOrNull()
        return (requested ?: DEFAULT_SAFE_MAX_PER_SECOND)
            .coerceIn(1, IB_HARD_LIMIT_PER_SECOND - 5)
    }

    fun minIntervalMs(): Long {
        val requested = System.getenv("DAY_TRADER_IB_MIN_INTERVAL_MS")?.toLongOrNull()
        val interval = requested ?: DEFAULT_MIN_INTERVAL_MS
        // Interval alone must not allow more than maxMessagesPerSecond.
        val maxFromInterval = 1000L / maxMessagesPerSecond()
        return interval.coerceAtLeast(maxFromInterval)
    }

    fun executionsRefreshIntervalMs(): Long =
        System.getenv("DAY_TRADER_IB_EXECUTIONS_REFRESH_MS")?.toLongOrNull()
            ?.coerceAtLeast(1_000L)
            ?: DEFAULT_EXECUTIONS_REFRESH_MS
}
