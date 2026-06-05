package daytrader.engine

object TouchTurnEngineConfig {
    const val LIQUIDITY_POLL_MS = 5_000L
    const val LIQUIDITY_POLL_EMULATOR_MS = 1_000L
    const val STOP_RULES_POLL_MS = 30_000L
    const val AUTO_START_POLL_MS = 1_000L
    const val CLOSED_BAR_REFETCH_RETRY_DELAY_MS = 2_000L
    const val CLOSED_BAR_REFETCH_MAX_ATTEMPTS = 8

    fun useEngine(): Boolean = true

    fun shadowLogEnabled(): Boolean =
        System.getenv("DAY_TRADER_TOUCH_TURN_ENGINE_SHADOW")?.equals("true", ignoreCase = true) == true
}
