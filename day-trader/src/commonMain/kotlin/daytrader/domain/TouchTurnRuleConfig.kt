package daytrader.domain

import daytrader.gateway.BrokerKind
import kotlinx.serialization.Serializable

/** Per-deployment enable flags for Touch Turn rules ([openDeadline] off by default). */
@Serializable
data class TouchTurnRuleEnables(
    /** Opening 15m range must meet ATR × ratio on daily ATR(14). */
    val liquidityRangeDailyAtr: Boolean = false,
    /** Auto-stop session after [TouchTurnRuleConfig.stopAfterOpenMinutes] from RTH open. */
    val openDeadline: Boolean = false,
    /** After a favorable move, convert the bracket stop to an IB adjustable trailing stop. */
    val adjustableTrailingStop: Boolean = true
) {
    companion object {
        val DEFAULT: TouchTurnRuleEnables = TouchTurnRuleEnables()
    }
}

fun TouchTurnRuleEnables.requiresLiquidityRange(): Boolean =
    liquidityRangeDailyAtr

fun TouchTurnRuleEnables.requiresDailyHistoricalBootstrap(): Boolean =
    liquidityRangeDailyAtr

@Serializable
data class TouchTurnRuleToggleDefinition(
    val key: String,
    val label: String,
    val description: String
)

/** Per-deployment Touch Turn rule thresholds (defaults match [TouchTurnDefaults]). */
@Serializable
data class TouchTurnRuleConfig(
    /** Min opening 15m range as a fraction of ATR to qualify as a liquidity candle. */
    val atrLiquidityRatio: Double = TouchTurnDefaults.ATR_LIQUIDITY_RATIO,
    /** Daily periods used to compute daily ATR for the liquidity threshold. */
    val dailyAtrLookbackPeriods: Int = TouchTurnDefaults.DAILY_ATR_LOOKBACK_PERIODS,
    /** Nudge entry limit inward from bar extreme — long up from low, short down from high (fraction of bar range). */
    val entryInwardOffsetRatioOfRange: Double = TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE,
    /** Green liquidity bar: take-profit distance as a fraction of bar range. */
    val takeProfitFibRatioGreen: Double = TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN,
    /** Red liquidity bar: take-profit distance as a fraction of bar range. */
    val takeProfitFibRatioRed: Double = TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_RED,
    /** Stop distance = entry-to-target distance ÷ this ratio (reward:risk). Higher = tighter stop. */
    val takeProfitToStopLossRatio: Double = TouchTurnDefaults.TAKE_PROFIT_TO_STOP_LOSS_RATIO,
    /** Wait after bar end before trusting a closed-bar historical refetch. */
    val closedBarRefetchSettleMs: Long = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS,
    /** Minutes after RTH open before auto-stop when [TouchTurnRuleEnables.openDeadline] is enabled. */
    val stopAfterOpenMinutes: Int = TouchTurnDefaults.STOP_AFTER_OPEN_MINUTES,
    /** Favorable move (fraction of entry→take-profit) before stop converts to trailing. */
    val trailingStopTriggerFractionOfEntryToTp: Double =
        TouchTurnDefaults.TRAILING_STOP_TRIGGER_FRACTION_OF_ENTRY_TO_TP,
    /** Trailing distance as a fraction of |entry−stop|; sent to IB as a nominal amount. */
    val trailingStopTrailFractionOfEntryToStop: Double =
        TouchTurnDefaults.TRAILING_STOP_TRAIL_FRACTION_OF_ENTRY_TO_STOP,
    /** Which entry-gate rules are enforced for this deployment. */
    val enables: TouchTurnRuleEnables = TouchTurnRuleEnables.DEFAULT
) {
    companion object {
        val DEFAULT: TouchTurnRuleConfig = TouchTurnRuleConfig()

        fun defaultForBrokerKind(kind: BrokerKind): TouchTurnRuleConfig =
            DEFAULT.copy(entryInwardOffsetRatioOfRange = kind.entryInwardOffsetRatioOfRangeDefault())
                .withLiquidityGatesForBrokerKind(kind)

        val toggleDefinitions: List<TouchTurnRuleToggleDefinition> = listOf(
            TouchTurnRuleToggleDefinition(
                key = "liquidityRangeDailyAtr",
                label = "Liquidity range (ATR)",
                description = "Opening 15m bar range must be at least 25% of daily ATR(14) on close."
            ),
            TouchTurnRuleToggleDefinition(
                key = "openDeadline",
                label = "RTH open deadline",
                description = "Stop the session and flatten working orders/position after the configured maximum " +
                    "minutes from regular-hours open."
            ),
            TouchTurnRuleToggleDefinition(
                key = "adjustableTrailingStop",
                label = "Adjustable trailing stop",
                description = "After price reaches the trail-arm level, convert the stop leg to an IB adjustable " +
                    "trailing stop using the configured trail distance."
            )
        )

        val fieldDefinitions: List<TouchTurnRuleFieldDefinition> = listOf(
            TouchTurnRuleFieldDefinition(
                key = "atrLiquidityRatio",
                label = "Liquidity range (× ATR)",
                description = "Opening 15m bar range must be at least this multiple of ATR14 to count as a " +
                    "liquidity candle. Higher = stricter (fewer trades).",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "dailyAtrLookbackPeriods",
                label = "ATR lookback (daily bars)",
                description = "Number of prior daily bars used to compute daily ATR(14) for the liquidity threshold.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "entryInwardOffsetRatioOfRange",
                label = "Entry inward offset (× range)",
                description = "Nudge the entry limit toward the bar middle: long entry moves up from bar low, " +
                    "short entry moves down from bar high, each by this fraction of bar range. 0 = bar extreme.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitFibRatioGreen",
                label = "Take profit — green bar (× range)",
                description = "Green liquidity bar (short): take-profit distance below entry as a fraction of bar range.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitFibRatioRed",
                label = "Take profit — red bar (× range)",
                description = "Red liquidity bar (long): take-profit distance above entry as a fraction of bar range.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitToStopLossRatio",
                label = "Take profit : stop loss ratio",
                description = "Stop distance is entry-to-target distance divided by this ratio. Default 2 = stop at " +
                    "half the take-profit distance (2:1 reward:risk). Higher values tighten the stop (smaller loss).",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "stopAfterOpenMinutes",
                label = "Max minutes after RTH open",
                description = "When RTH open deadline is enabled, the session auto-stops and flattens broker " +
                    "orders/position this many minutes after the session's RTH open anchor.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "trailingStopTriggerFractionOfEntryToTp",
                label = "Trail arm (fraction to TP)",
                description = "When adjustable trailing is enabled, price must reach entry plus this fraction of " +
                    "the entry-to-take-profit distance before the stop converts to trailing. Default 0.5 = halfway " +
                    "to target. Must be between 0 and 1, and at least (trail distance − 1) ÷ take-profit:stop ratio " +
                    "so trailing does not widen beyond the initial fixed stop when it arms.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "trailingStopTrailFractionOfEntryToStop",
                label = "Trail distance (fraction of risk)",
                description = "Nominal trailing distance as a fraction of |entry−stop| once trailing is armed. " +
                    "Default 0.5 = half the initial risk. Must fit the trail arm and take-profit:stop ratio " +
                    "(trail arm × ratio ≥ trail distance − 1).",
                kind = TouchTurnRuleFieldKind.RATIO
            )
        )

        fun valueForField(config: TouchTurnRuleConfig, key: String): String = when (key) {
            "atrLiquidityRatio" -> config.atrLiquidityRatio.toString()
            "dailyAtrLookbackPeriods" -> config.dailyAtrLookbackPeriods.toString()
            "entryInwardOffsetRatioOfRange" -> config.entryInwardOffsetRatioOfRange.toString()
            "takeProfitFibRatioGreen" -> config.takeProfitFibRatioGreen.toString()
            "takeProfitFibRatioRed" -> config.takeProfitFibRatioRed.toString()
            "takeProfitToStopLossRatio" -> config.takeProfitToStopLossRatio.toString()
            "stopAfterOpenMinutes" -> config.stopAfterOpenMinutes.toString()
            "trailingStopTriggerFractionOfEntryToTp" ->
                config.trailingStopTriggerFractionOfEntryToTp.toString()
            "trailingStopTrailFractionOfEntryToStop" ->
                config.trailingStopTrailFractionOfEntryToStop.toString()
            else -> ""
        }

        fun withFieldValue(config: TouchTurnRuleConfig, key: String, raw: String): TouchTurnRuleConfig? {
            return when (fieldDefinitions.firstOrNull { it.key == key }?.kind) {
                TouchTurnRuleFieldKind.INTEGER -> {
                    val intValue = raw.trim().toIntOrNull() ?: return null
                    if (intValue <= 0) return null
                    when (key) {
                        "dailyAtrLookbackPeriods" -> config.copy(dailyAtrLookbackPeriods = intValue)
                        "stopAfterOpenMinutes" -> config.copy(stopAfterOpenMinutes = intValue)
                        else -> null
                    }
                }
                TouchTurnRuleFieldKind.RATIO, TouchTurnRuleFieldKind.PRICE -> {
                    val doubleValue = raw.trim().toDoubleOrNull() ?: return null
                    when (key) {
                        "entryInwardOffsetRatioOfRange" -> {
                            if (doubleValue < 0.0) return null
                            config.copy(entryInwardOffsetRatioOfRange = doubleValue)
                        }
                        else -> {
                            if (doubleValue <= 0.0) return null
                            when (key) {
                                "atrLiquidityRatio" -> config.copy(atrLiquidityRatio = doubleValue)
                                "takeProfitFibRatioGreen" -> config.copy(takeProfitFibRatioGreen = doubleValue)
                                "takeProfitFibRatioRed" -> config.copy(takeProfitFibRatioRed = doubleValue)
                                "takeProfitToStopLossRatio" -> {
                                    val candidate = config.copy(takeProfitToStopLossRatio = doubleValue)
                                    if (candidate.trailingStopFractionsInvalid()) return null
                                    candidate
                                }
                                "trailingStopTriggerFractionOfEntryToTp" -> {
                                    val candidate = config.copy(
                                        trailingStopTriggerFractionOfEntryToTp = doubleValue
                                    )
                                    if (candidate.trailingStopFractionsInvalid()) return null
                                    candidate
                                }
                                "trailingStopTrailFractionOfEntryToStop" -> {
                                    val candidate = config.copy(
                                        trailingStopTrailFractionOfEntryToStop = doubleValue
                                    )
                                    if (candidate.trailingStopFractionsInvalid()) return null
                                    candidate
                                }
                                else -> null
                            }
                        }
                    }
                }
                else -> null
            }
        }

        fun isToggleEnabled(config: TouchTurnRuleConfig, key: String): Boolean = when (key) {
            "liquidityRangeDailyAtr" -> config.enables.liquidityRangeDailyAtr
            "openDeadline" -> config.enables.openDeadline
            "adjustableTrailingStop" -> config.enables.adjustableTrailingStop
            else -> true
        }

        fun withToggleEnabled(config: TouchTurnRuleConfig, key: String, enabled: Boolean): TouchTurnRuleConfig {
            val enables = when (key) {
                "liquidityRangeDailyAtr" -> config.enables.copy(liquidityRangeDailyAtr = enabled)
                "openDeadline" -> config.enables.copy(openDeadline = enabled)
                "adjustableTrailingStop" -> config.enables.copy(adjustableTrailingStop = enabled)
                else -> config.enables
            }
            return config.copy(enables = enables)
        }
    }

    /** IB adjustable-stop parameters when [enables.adjustableTrailingStop] is on; null when trailing disabled. */
    fun computeAdjustableStop(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double
    ): TouchTurnAdjustableStopParams? {
        if (!enables.adjustableTrailingStop) return null
        if (trailingStopFractionsInvalid()) return null
        return TouchTurnAdjustableStop.compute(
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            triggerFraction = trailingStopTriggerFractionOfEntryToTp,
            trailFraction = trailingStopTrailFractionOfEntryToStop
        )
    }

    /** Human-readable reason when trailing fractions disagree with [takeProfitToStopLossRatio]; null if valid. */
    fun trailingStopFractionsValidationError(): String? =
        TouchTurnAdjustableStop.validateFractions(
            triggerFraction = trailingStopTriggerFractionOfEntryToTp,
            trailFraction = trailingStopTrailFractionOfEntryToStop,
            takeProfitToStopLossRatio = takeProfitToStopLossRatio
        )

    private fun trailingStopFractionsInvalid(): Boolean = trailingStopFractionsValidationError() != null
}

@Serializable
enum class TouchTurnRuleFieldKind {
    RATIO,
    PRICE,
    INTEGER,
    MILLISECONDS
}

@Serializable
data class TouchTurnRuleFieldDefinition(
    val key: String,
    val label: String,
    val description: String,
    val kind: TouchTurnRuleFieldKind
)

fun StrategyDeployment.effectiveTouchTurnRules(): TouchTurnRuleConfig =
    touchTurnSession?.rules ?: touchTurnRules

fun BrokerKind.entryInwardOffsetRatioOfRangeDefault(): Double =
    when (this) {
        BrokerKind.EMULATOR, BrokerKind.REPLAY ->
            TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE_SIMULATED
        BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA ->
            TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE
    }

fun BrokerKind.defaultLiquidityRangeDailyAtrEnabled(): Boolean = when (this) {
    BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> true
    BrokerKind.EMULATOR, BrokerKind.REPLAY -> false
}

fun TouchTurnRuleConfig.withLiquidityGatesForBrokerKind(kind: BrokerKind): TouchTurnRuleConfig {
    val targetEnables = enables.copy(
        liquidityRangeDailyAtr = kind.defaultLiquidityRangeDailyAtrEnabled()
    )
    return if (targetEnables == enables) this else copy(enables = targetEnables)
}

fun TouchTurnRuleConfig.withEntryInwardOffsetForBrokerKind(kind: BrokerKind): TouchTurnRuleConfig {
    val target = kind.entryInwardOffsetRatioOfRangeDefault()
    return if (entryInwardOffsetRatioOfRange == target) this else copy(entryInwardOffsetRatioOfRange = target)
}

/** Ensures legacy [openDeadline] stays off on persisted configs that still have it enabled. */
fun TouchTurnRuleConfig.withNewConfigurableRulesDisabled(): TouchTurnRuleConfig {
    val normalizedEnables = enables.copy(openDeadline = false)
    return if (normalizedEnables == enables) this else copy(enables = normalizedEnables)
}

fun StrategyDeployment.withLiquidityGatesForBrokerKind(kind: BrokerKind): StrategyDeployment {
    val rules = touchTurnRules.withLiquidityGatesForBrokerKind(kind)
    val session = touchTurnSession?.let { ctx ->
        val patchedRules = ctx.rules.withLiquidityGatesForBrokerKind(kind)
        if (patchedRules == ctx.rules) ctx else ctx.copy(rules = patchedRules)
    }
    val history = sessionHistory.map { day ->
        val record = day.touchTurnRunRecord ?: return@map day
        val runRules = record.rules ?: return@map day
        val patchedRules = runRules.withLiquidityGatesForBrokerKind(kind)
        if (patchedRules == runRules) day else day.copy(touchTurnRunRecord = record.copy(rules = patchedRules))
    }
    if (rules == touchTurnRules && session == touchTurnSession && history == sessionHistory) return this
    return copy(touchTurnRules = rules, touchTurnSession = session, sessionHistory = history)
}

fun StrategyDeployment.withEntryInwardOffsetForBrokerKind(kind: BrokerKind): StrategyDeployment {
    val rules = touchTurnRules.withEntryInwardOffsetForBrokerKind(kind)
    val session = touchTurnSession?.let { ctx ->
        val patchedRules = ctx.rules.withEntryInwardOffsetForBrokerKind(kind)
        if (patchedRules == ctx.rules) ctx else ctx.copy(rules = patchedRules)
    }
    val history = sessionHistory.map { day ->
        val record = day.touchTurnRunRecord ?: return@map day
        val runRules = record.rules ?: return@map day
        val patchedRules = runRules.withEntryInwardOffsetForBrokerKind(kind)
        if (patchedRules == runRules) day else day.copy(touchTurnRunRecord = record.copy(rules = patchedRules))
    }
    if (rules == touchTurnRules && session == touchTurnSession && history == sessionHistory) return this
    return copy(touchTurnRules = rules, touchTurnSession = session, sessionHistory = history)
}

/** Patches deployment and in-flight session rules so [openDeadline] stays disabled on legacy configs. */
fun StrategyDeployment.withNewConfigurableTouchTurnRulesDisabled(): StrategyDeployment {
    val rules = touchTurnRules.withNewConfigurableRulesDisabled()
    val session = touchTurnSession?.let { ctx ->
        val sessionRules = ctx.rules.withNewConfigurableRulesDisabled()
        if (sessionRules == ctx.rules) ctx else ctx.copy(rules = sessionRules)
    }
    if (rules == touchTurnRules && session == touchTurnSession) return this
    return copy(touchTurnRules = rules, touchTurnSession = session)
}
