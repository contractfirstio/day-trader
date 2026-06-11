package daytrader.replay

object ReplayPlaybackConfig {
    /** Wall-clock time to simulate the 15m RTH opening bar (0 = instant jump). */
    const val FORMING_WALL_DURATION_MS = 2_000L

    /** Steps during forming fast-forward (smoother UI updates). */
    const val FORMING_STEPS = 20

    /** Wall-clock gap between quote publishes after the opening bar closes. */
    const val QUOTE_INTERVAL_MS = 10L

    /** How often to nudge [daytrader.engine.TouchTurnCommand.PollLiquidity] during quote drip. */
    const val LIQUIDITY_NUDGE_EVERY_N_QUOTES = 50

    /** Max polls while waiting for closed-bar refetch after fast-forward. */
    const val CLOSED_BAR_WAIT_POLLS = 80

    const val CLOSED_BAR_WAIT_POLL_MS = 15L
}
