package daytrader.broker.emulator

import daytrader.domain.MacroTrendState
import daytrader.domain.StockTrendState

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
     * Default 0.67 so simulated outcomes land near 67% TP / 33% stop over many runs.
     */
    val bracketExitTakeProfitProbability: Double = 0.67,
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
     * Touch Turn session bootstrap (refetch reuses the same index — see [BrokerEmulatorEngine]).
     */
    val alternateFirstCandleColor: Boolean = true,
    /**
     * When true, Touch Turn entry limits fill as soon as the bracket is placed (legacy behavior).
     * Default false: price must reach the entry over one or more market ticks.
     */
    val touchTurnEntryFillImmediately: Boolean = false,
    /** Probability the entry limit is never touched (price walks away). Ignored when [touchTurnEntryFillImmediately]. */
    val touchTurnEntryNeverFillProbability: Double = 0.0,
    /** Minimum market ticks before an approaching entry may fill (simulates time to reach the level). */
    val touchTurnEntryMinApproachTicks: Int = 2,
    /** Fraction of bracket range used as initial distance from entry when approaching. */
    val touchTurnEntryStartOffsetPctOfRange: Double = 0.60,
    /** Per-tick step size while approaching entry or drifting away (fraction of bracket range). */
    val touchTurnEntryStepPctOfRange: Double = 0.10,
    /** Bid/ask half-width as a fraction of bracket range (synthetic quote book). */
    val emulatorQuoteSpreadPctOfRange: Double = 0.02,
    /** When set, every Touch Turn bracket uses this entry scenario (tests / debugging). */
    val touchTurnEntryScenarioOverride: TouchTurnEntryScenario? = null,
    /**
     * Price feed for order fill triggers. [EmulatorPricingSource.SYNTHETIC] walks quotes inside
     * the emulator; [EmulatorPricingSource.LIVE_EXCHANGE] uses [BrokerEmulatorEngine.ingestExternalQuote].
     */
    val pricingSource: EmulatorPricingSource = EmulatorPricingSource.SYNTHETIC,
    /** Override home-market macro trend by RTH zone id (tests / experiments). */
    val homeMacroTrendByZone: Map<String, MacroTrendState> = emptyMap(),
    /** Override symbol stock trend by normalized symbol (tests / experiments). */
    val stockTrendBySymbol: Map<String, StockTrendState> = emptyMap(),
    /**
     * When true, each captured replay quote is ingested synchronously for fill evaluation instead
     * of coalescing to the latest tick every 50ms (see [EmulatorBrokerAdapter]).
     */
    val flushEachExternalQuote: Boolean = false
) {
    /** @see pricingSource */
    val useLiveIbMarketData: Boolean
        get() = pricingSource == EmulatorPricingSource.LIVE_EXCHANGE
    companion object {
        val Default = BrokerEmulatorConfig()

        fun fromEnvironment(): BrokerEmulatorConfig {
            val seconds = parseFirstCandleSecondsUntilClose(emulatorFirstCandleCloseSecEnv())
            return Default.copy(
                firstCandleSecondsUntilClose = seconds,
                firstCandleColorMode = EmulatorFirstCandleColorMode.parse(emulatorFirstCandleColorEnv()),
                alternateFirstCandleColor = parseFirstCandleAlternate(emulatorFirstCandleAlternateEnv()),
                touchTurnEntryFillImmediately = parseEntryFillImmediately(emulatorEntryFillImmediatelyEnv()),
                touchTurnEntryNeverFillProbability = parseEntryNeverFillProbability(emulatorEntryNeverFillProbEnv())
            )
        }

        fun forLiveIbMarketData(): BrokerEmulatorConfig =
            Default.copy(
                pricingSource = EmulatorPricingSource.LIVE_EXCHANGE,
                firstCandleSecondsUntilClose = null,
                firstCandleColorMode = EmulatorFirstCandleColorMode.AUTO,
                alternateFirstCandleColor = false,
                simulateOrderProgress = false,
                bracketExitSpreadWidenFactor = 1.0,
                touchTurnEntryFillImmediately = false,
                touchTurnEntryNeverFillProbability = 0.0,
                touchTurnEntryScenarioOverride = null
            )

        /**
         * Session replay backtest: captured quotes drive fills; entry uses approach simulation only
         * when synthetic pricing is active. Random bracket-walk knobs are neutralized and entry
         * scenario is fixed so repeated runs with the same capture are stable.
         */
        fun forReplayBacktest(): BrokerEmulatorConfig =
            forLiveIbMarketData().copy(
                connectDelayMs = 1,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.APPROACH_AND_FILL,
                touchTurnEntryNeverFillProbability = 0.0,
                bracketExitTakeProfitProbability = 0.5,
                bracketWalkSteerTowardTargetProbability = 1.0,
                bracketWalkDirectionFlipChance = 0.0,
                flushEachExternalQuote = true
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

        internal fun parseEntryFillImmediately(raw: String?): Boolean =
            when (raw?.trim()?.lowercase()) {
                "true", "1", "on", "yes" -> true
                else -> false
            }

        internal fun parseEntryNeverFillProbability(raw: String?): Double =
            raw?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: Default.touchTurnEntryNeverFillProbability
    }
}

expect fun emulatorFirstCandleCloseSecEnv(): String?

/** `green`/`short`, `red`/`long`, or `auto` (default). */
expect fun emulatorFirstCandleColorEnv(): String?

/** `false` to keep a stable color per symbol/day when mode is `auto`. */
expect fun emulatorFirstCandleAlternateEnv(): String?

/** `true` for instant entry fill on bracket place (legacy). */
expect fun emulatorEntryFillImmediatelyEnv(): String?

/** `0`–`1` chance entry is never touched (default 0.25). */
expect fun emulatorEntryNeverFillProbEnv(): String?
