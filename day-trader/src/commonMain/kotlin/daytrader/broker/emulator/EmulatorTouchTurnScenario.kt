package daytrader.broker.emulator

/**
 * Deterministic Touch Turn emulator fixtures for fast end-to-end manual testing.
 *
 * Static 15m + 5m bars; only bracket exit (TP vs SL) varies — fixed per scenario or random
 * for [GREEN_SHORT] / [RED_LONG].
 */
enum class EmulatorTouchTurnScenario {
    /** Green 15m opening bar → short fade; bracket exits at take-profit. */
    GREEN_SHORT_TP,
    /** Green 15m → short; bracket exits at stop-loss. */
    GREEN_SHORT_SL,
    /** Red 15m opening bar → long fade; bracket exits at take-profit. */
    RED_LONG_TP,
    /** Red 15m → long; bracket exits at stop-loss. */
    RED_LONG_SL,
    /** Green 15m → short; TP/SL from [BrokerEmulatorConfig.bracketExitTakeProfitProbability]. */
    GREEN_SHORT,
    /** Red 15m → long; TP/SL from [BrokerEmulatorConfig.bracketExitTakeProfitProbability]. */
    RED_LONG;

    val isGreenOpeningBar: Boolean
        get() = when (this) {
            GREEN_SHORT_TP, GREEN_SHORT_SL, GREEN_SHORT -> true
            RED_LONG_TP, RED_LONG_SL, RED_LONG -> false
        }

    /** When non-null, bracket walk always exits at this leg. */
    val forcedTakeProfitExit: Boolean?
        get() = when (this) {
            GREEN_SHORT_TP, RED_LONG_TP -> true
            GREEN_SHORT_SL, RED_LONG_SL -> false
            GREEN_SHORT, RED_LONG -> null
        }

    companion object {
        fun parse(raw: String?): EmulatorTouchTurnScenario? {
            val normalized = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
            if (normalized.isBlank() || normalized == "off" || normalized == "dynamic") return null
            return when (normalized) {
                "green_short_tp", "green_tp", "short_tp" -> GREEN_SHORT_TP
                "green_short_sl", "green_sl", "short_sl" -> GREEN_SHORT_SL
                "red_long_tp", "red_tp", "long_tp" -> RED_LONG_TP
                "red_long_sl", "red_sl", "long_sl" -> RED_LONG_SL
                "green_short", "green", "short" -> GREEN_SHORT
                "red_long", "red", "long" -> RED_LONG
                else -> entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            }
        }
    }
}

/** Applies fast-path defaults when [BrokerEmulatorConfig.touchTurnScenario] is active. */
fun BrokerEmulatorConfig.withTouchTurnScenarioDefaults(): BrokerEmulatorConfig {
    val scenario = touchTurnScenario ?: return this
    return copy(
        firstCandleColorMode = if (scenario.isGreenOpeningBar) {
            EmulatorFirstCandleColorMode.GREEN
        } else {
            EmulatorFirstCandleColorMode.RED
        },
        alternateFirstCandleColor = false,
        firstCandleSecondsUntilClose = null,
        touchTurnEntryFillImmediately = true,
        touchTurnEntryNeverFillProbability = 0.0,
        fiveMinuteBarSecondsUntilClose = 0L,
        fiveMinuteHammerBarIndex = 0,
        historicalDelayMs = 1L,
        marketTickIntervalMs = 500L,
        bracketExitMinWalkTicks = 25,
        bracketWalkStepPctOfRange = 0.012,
        bracketWalkSteerTowardTargetProbability = 1.0,
        bracketWalkDirectionFlipChance = 0.0,
        bracketExitTakeProfitProbability = scenario.forcedTakeProfitExit?.let { if (it) 1.0 else 0.0 }
            ?: bracketExitTakeProfitProbability
    )
}
