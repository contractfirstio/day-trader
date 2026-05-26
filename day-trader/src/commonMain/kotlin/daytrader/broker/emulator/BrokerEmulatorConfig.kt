package daytrader.broker.emulator

/**
 * Controls the synthetic first 15m bar for Touch Turn (green → short, red → long).
 * [AUTO] uses symbol/day hashing; [ALTERNATE] flips on each emulator candle fetch when enabled in config.
 */
enum class EmulatorFirstCandleColorMode {
    AUTO,
    GREEN,
    RED;

    companion object {
        fun parse(raw: String?): EmulatorFirstCandleColorMode = when (raw?.trim()?.lowercase()) {
            "green", "short" -> GREEN
            "red", "long" -> RED
            null, "", "auto" -> AUTO
            else -> AUTO
        }
    }
}

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
    /**
     * When placing a bracket, probability the simulated exit is take-profit (vs stop).
     * Set to 0.5 because entry is closer to stop than TP in Touch Turn math — an unbiased
     * random walk would skew heavily toward stop losses.
     */
    val bracketExitTakeProfitProbability: Double = 0.5,
    /** How often each tick steps toward the pre-selected [BracketExitTarget] (vs oscillating away). */
    val bracketWalkSteerTowardTargetProbability: Double = 0.88,
    val historicalDelayMs: Long = 120L,
    val simulateOrderProgress: Boolean = true,
    val orderProgressIntervalMs: Long = 8_000L,
    /**
     * When set, the first 15m Touch Turn bar is time-shifted so it closes after this many
     * seconds (wall clock). The bar is still 15 minutes long in domain logic.
     * Null = legacy fixed today 09:30 open (often already closed).
     */
    val firstCandleSecondsUntilClose: Long? = 10L,
    /** Force first 15m bar color; [EmulatorFirstCandleColorMode.AUTO] uses symbol/day or alternation. */
    val firstCandleColorMode: EmulatorFirstCandleColorMode = EmulatorFirstCandleColorMode.AUTO,
    /**
     * When [firstCandleColorMode] is [EmulatorFirstCandleColorMode.AUTO], alternate green/red on each
     * [BrokerEmulatorEngine.fetchFirstFifteenMinuteCandle] call (easy long/short testing).
     */
    val alternateFirstCandleColor: Boolean = true,
    /**
     * When true, [BrokerEmulatorEngine] does not synthesize price walks; marks come from
     * [BrokerEmulatorEngine.ingestLiveMark] (hybrid paper + live IB data mode).
     */
    val useLiveIbMarketData: Boolean = false
) {
    companion object {
        val Default = BrokerEmulatorConfig()

        fun fromEnvironment(): BrokerEmulatorConfig {
            val seconds = parseFirstCandleSecondsUntilClose(emulatorFirstCandleCloseSecEnv())
            return Default.copy(
                firstCandleSecondsUntilClose = seconds,
                firstCandleColorMode = EmulatorFirstCandleColorMode.parse(emulatorFirstCandleColorEnv()),
                alternateFirstCandleColor = parseFirstCandleAlternate(emulatorFirstCandleAlternateEnv())
            )
        }

        fun forLiveIbMarketData(): BrokerEmulatorConfig =
            fromEnvironment().copy(
                useLiveIbMarketData = true,
                firstCandleSecondsUntilClose = null,
                simulateOrderProgress = false,
                bracketExitSpreadWidenFactor = 1.0
            )

        internal fun parseFirstCandleSecondsUntilClose(raw: String?): Long? =
            when {
                raw == null || raw.isBlank() -> 10L
                raw.equals("off", ignoreCase = true) -> null
                else -> raw.toLongOrNull()?.takeIf { it > 0 }
            }

        internal fun parseFirstCandleAlternate(raw: String?): Boolean =
            when (raw?.trim()?.lowercase()) {
                null, "", "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> true
            }
    }
}

expect fun emulatorFirstCandleCloseSecEnv(): String?

/** `green`/`short`, `red`/`long`, or `auto` (default). */
expect fun emulatorFirstCandleColorEnv(): String?

/** `false` to keep a stable color per symbol/day when mode is `auto`. */
expect fun emulatorFirstCandleAlternateEnv(): String?
