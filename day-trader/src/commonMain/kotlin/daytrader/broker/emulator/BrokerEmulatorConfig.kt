package daytrader.broker.emulator

data class BrokerEmulatorConfig(
    val accountId: String = "EMU001",
    val connectDelayMs: Long = 350L,
    val reconnectDelayMs: Long = 500L,
    val marketTickIntervalMs: Long = 2_000L,
    val marketTickJitterPct: Double = 0.0012,
    /** After a Touch Turn bracket entry fills, oscillate price between stop and TP (fraction of range per tick). */
    val bracketWalkStepPctOfRange: Double = 0.12,
    /** Chance each tick to reverse bracket walk direction (keeps price moving up/down the range). */
    val bracketWalkDirectionFlipChance: Double = 0.25,
    /** Emulator widens stop and take-profit away from entry (>1 = wider bracket). */
    val bracketExitSpreadWidenFactor: Double = 1.35,
    /** Probability the bracket price walk moves toward take-profit (vs stop) when choosing direction. */
    val bracketWalkTakeProfitBias: Double = 0.70,
    val historicalDelayMs: Long = 120L,
    val simulateOrderProgress: Boolean = true,
    val orderProgressIntervalMs: Long = 8_000L,
    /**
     * When set, the first 15m Touch Turn bar is time-shifted so it closes after this many
     * seconds (wall clock). The bar is still 15 minutes long in domain logic.
     * Null = legacy fixed today 09:30 open (often already closed).
     */
    val firstCandleSecondsUntilClose: Long? = 10L
) {
    companion object {
        val Default = BrokerEmulatorConfig()

        fun fromEnvironment(): BrokerEmulatorConfig {
            val seconds = parseFirstCandleSecondsUntilClose(emulatorFirstCandleCloseSecEnv())
            return if (seconds == Default.firstCandleSecondsUntilClose) {
                Default
            } else {
                Default.copy(firstCandleSecondsUntilClose = seconds)
            }
        }

        internal fun parseFirstCandleSecondsUntilClose(raw: String?): Long? =
            when {
                raw == null || raw.isBlank() -> 10L
                raw.equals("off", ignoreCase = true) -> null
                else -> raw.toLongOrNull()?.takeIf { it > 0 }
            }
    }
}

expect fun emulatorFirstCandleCloseSecEnv(): String?
