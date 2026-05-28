package daytrader.engine

object TouchTurnEngineConfig {
    const val LIQUIDITY_POLL_MS = 5_000L
    const val STOP_RULES_POLL_MS = 30_000L
    const val AUTO_START_POLL_MS = 1_000L

    fun useEngine(): Boolean = true

    fun shadowLogEnabled(): Boolean =
        System.getenv("DAY_TRADER_TOUCH_TURN_ENGINE_SHADOW")?.equals("true", ignoreCase = true) == true
}
