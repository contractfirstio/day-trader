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
    val adjustableTrailingStop: Boolean = true,
    /** Wait for a 5m hammer after the 15m liquidity sweep before market entry (Touch Turn only). */
    val fiveMinuteConfirmation: Boolean = false
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
enum class TouchTurnRuleCategory(
    val label: String,
    val toggleKey: String?,
    /** Threshold fields stay visible even when the category toggle is off. */
    val fieldsAlwaysVisible: Boolean = false
) {
    LIQUIDITY("Liquidity", "liquidityRangeDailyAtr"),
    BRACKET("Bracket sizing", null, fieldsAlwaysVisible = true),
    SESSION_DEADLINE("Session deadline", "openDeadline"),
    TRAILING_STOP("Trailing stop", "adjustableTrailingStop"),
    CONFIRMATION("5-minute confirmation", "fiveMinuteConfirmation"),
    TRADE_MODE("Trade mode", "invertTradeSide"),
}

@Serializable
data class TouchTurnRuleToggleDefinition(
    val key: String,
    val label: String,
    val description: String,
    val category: TouchTurnRuleCategory
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
    /** When trailing arms, stop is placed this fraction of the way from entry toward the initial stop. */
    val trailingStopArmFractionOfEntryToStop: Double =
        TouchTurnDefaults.TRAILING_STOP_ARM_FRACTION_OF_ENTRY_TO_STOP,
    /**
     * Minimum projected gross profit (|take-profit − entry| × quantity) before any bracket is
     * submitted. In the symbol's trading currency; 0 disables the gate.
     */
    val minGrossProfit: Double = TouchTurnDefaults.MIN_GROSS_PROFIT,
    /** Which entry-gate rules are enforced for this deployment. */
    val enables: TouchTurnRuleEnables = TouchTurnRuleEnables.DEFAULT,
    /**
     * Same entry levels as reversal mode but long/short flipped (continuation bet).
     */
    val invertTradeSide: Boolean = false
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
                description = "Opening 15m bar range must be at least 25% of daily ATR(14) on close.",
                category = TouchTurnRuleCategory.LIQUIDITY
            ),
            TouchTurnRuleToggleDefinition(
                key = "openDeadline",
                label = "RTH open deadline",
                description = "Stop the session and flatten working orders/position after the configured maximum " +
                    "minutes from regular-hours open.",
                category = TouchTurnRuleCategory.SESSION_DEADLINE
            ),
            TouchTurnRuleToggleDefinition(
                key = "adjustableTrailingStop",
                label = "Adjustable trailing stop",
                description = "After price reaches the trail-arm level, move the stop toward the initial stop " +
                    "(by the configured entry-to-stop fraction) and ratchet it up (long) or down (short) as price " +
                    "continues in your favor.",
                category = TouchTurnRuleCategory.TRAILING_STOP
            ),
            TouchTurnRuleToggleDefinition(
                key = "fiveMinuteConfirmation",
                label = "5-minute hammer confirmation",
                description = "After a 15m liquidity sweep, wait up to three 5m bars for a hammer that closes " +
                    "inside the sweep range, then enter at market using the original 15m stop and take-profit. " +
                    "Rejected when projected gross profit to the 15m target is below the configured minimum. " +
                    "Available only in Touch Turn (reversal) mode — hidden when invert trade side is on.",
                category = TouchTurnRuleCategory.CONFIRMATION
            ),
            TouchTurnRuleToggleDefinition(
                key = "invertTradeSide",
                label = "Invert trade side (continuation)",
                description = "Same entry levels as Touch and Turn, but long where reversal would short and vice " +
                    "versa. Uses a stop entry on breakout instead of a resting limit.",
                category = TouchTurnRuleCategory.TRADE_MODE
            )
        )

        val fieldDefinitions: List<TouchTurnRuleFieldDefinition> = listOf(
            TouchTurnRuleFieldDefinition(
                key = "atrLiquidityRatio",
                label = "Liquidity range (× ATR)",
                description = "Opening 15m bar range must be at least this multiple of ATR14 to count as a " +
                    "liquidity candle. Higher = stricter (fewer trades).",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.LIQUIDITY,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "dailyAtrLookbackPeriods",
                label = "ATR lookback (daily bars)",
                description = "Number of prior daily bars used to compute daily ATR(14) for the liquidity threshold.",
                kind = TouchTurnRuleFieldKind.INTEGER,
                category = TouchTurnRuleCategory.LIQUIDITY,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitFibRatioGreen",
                label = "Take profit — green bar (× range)",
                description = "Green liquidity bar (short): take-profit distance below entry as a fraction of bar range.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.BRACKET,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitFibRatioRed",
                label = "Take profit — red bar (× range)",
                description = "Red liquidity bar (long): take-profit distance above entry as a fraction of bar range.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.BRACKET,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitToStopLossRatio",
                label = "Take profit : stop loss ratio",
                description = "Stop distance is entry-to-target distance divided by this ratio. Default 2 = stop at " +
                    "half the take-profit distance (2:1 reward:risk). Higher values tighten the stop (smaller loss).",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.BRACKET,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "entryInwardOffsetRatioOfRange",
                label = "Entry inward offset (× range)",
                description = "Nudge the entry limit toward the bar middle: long entry moves up from bar low, " +
                    "short entry moves down from bar high, each by this fraction of bar range. 0 = bar extreme. " +
                    "Depends on broker mode — not reset by the global defaults button.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.BRACKET,
                defaultable = false
            ),
            TouchTurnRuleFieldDefinition(
                key = "stopAfterOpenMinutes",
                label = "Max minutes after RTH open",
                description = "When RTH open deadline is enabled, the session auto-stops and flattens broker " +
                    "orders/position this many minutes after the session's RTH open anchor.",
                kind = TouchTurnRuleFieldKind.INTEGER,
                category = TouchTurnRuleCategory.SESSION_DEADLINE,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "trailingStopTriggerFractionOfEntryToTp",
                label = "Trail arm (fraction to TP)",
                description = "When adjustable trailing is enabled, price must reach entry plus this fraction of " +
                    "the entry-to-take-profit distance before the stop moves to the arm level and begins trailing. " +
                    "Default 0.5 = halfway to target. Must be between 0 and 1.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.TRAILING_STOP,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "trailingStopArmFractionOfEntryToStop",
                label = "Trail arm (fraction to stop)",
                description = "When trailing activates, place the first trailing stop this fraction of the way " +
                    "from entry toward the initial stop. 0 = at entry (breakeven). 0.5 = halfway between entry and " +
                    "stop. 1 = unchanged at the initial stop. Must be between 0 and 1.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.TRAILING_STOP,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "minGrossProfit",
                label = "Min gross profit",
                description = "Before submitting a bracket, require projected gross profit to the " +
                    "take-profit (|TP − entry| × quantity) to be at least this amount in the symbol's " +
                    "currency. Applies to the default 15m entry and to 5m hammer-confirmed market entry. " +
                    "0 disables the gate.",
                kind = TouchTurnRuleFieldKind.PRICE,
                category = TouchTurnRuleCategory.BRACKET,
                defaultable = true
            )
        )

        fun fieldsForCategory(category: TouchTurnRuleCategory): List<TouchTurnRuleFieldDefinition> =
            fieldDefinitions.filter { it.category == category }

        /** Defaultable thresholds first, then mandatory fields without a global default. */
        fun fieldsForCategoryDisplay(category: TouchTurnRuleCategory): List<TouchTurnRuleFieldDefinition> =
            fieldsForCategory(category).sortedWith(
                compareByDescending<TouchTurnRuleFieldDefinition> { it.defaultable }.thenBy { it.label }
            )

        fun toggleForCategory(category: TouchTurnRuleCategory): TouchTurnRuleToggleDefinition? =
            category.toggleKey?.let { key -> toggleDefinitions.find { it.key == key } }

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
            "trailingStopArmFractionOfEntryToStop" ->
                config.trailingStopArmFractionOfEntryToStop.toString()
            "minGrossProfit" -> config.minGrossProfit.toString()
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
                        "minGrossProfit" -> {
                            if (doubleValue < 0.0) return null
                            config.copy(minGrossProfit = doubleValue)
                        }
                        "trailingStopArmFractionOfEntryToStop" -> {
                            if (doubleValue < 0.0 || doubleValue > 1.0) return null
                            val candidate = config.copy(
                                trailingStopArmFractionOfEntryToStop = doubleValue
                            )
                            if (candidate.trailingStopValidationError() != null) return null
                            candidate
                        }
                        else -> {
                            if (doubleValue <= 0.0) return null
                            when (key) {
                                "atrLiquidityRatio" -> config.copy(atrLiquidityRatio = doubleValue)
                                "takeProfitFibRatioGreen" -> config.copy(takeProfitFibRatioGreen = doubleValue)
                                "takeProfitFibRatioRed" -> config.copy(takeProfitFibRatioRed = doubleValue)
                                "takeProfitToStopLossRatio" -> config.copy(takeProfitToStopLossRatio = doubleValue)
                                "trailingStopTriggerFractionOfEntryToTp" -> {
                                    val candidate = config.copy(
                                        trailingStopTriggerFractionOfEntryToTp = doubleValue
                                    )
                                    if (candidate.trailingStopConfigInvalid()) return null
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
            "fiveMinuteConfirmation" -> config.enables.fiveMinuteConfirmation
            "invertTradeSide" -> config.invertTradeSide
            else -> true
        }

        /** 5m confirmation is a reversal-mode feature; not shown or applied when invert is on. */
        fun isFiveMinuteConfirmationVisible(config: TouchTurnRuleConfig): Boolean = !config.invertTradeSide

        /** Stored preference AND applicable for the current trade mode. */
        fun isFiveMinuteConfirmationEffective(config: TouchTurnRuleConfig): Boolean =
            isFiveMinuteConfirmationVisible(config) && config.enables.fiveMinuteConfirmation

        fun withToggleEnabled(config: TouchTurnRuleConfig, key: String, enabled: Boolean): TouchTurnRuleConfig =
            when (key) {
                "invertTradeSide" -> config.copy(invertTradeSide = enabled)
                else -> {
                    val enables = when (key) {
                        "liquidityRangeDailyAtr" -> config.enables.copy(liquidityRangeDailyAtr = enabled)
                        "openDeadline" -> config.enables.copy(openDeadline = enabled)
                        "adjustableTrailingStop" -> config.enables.copy(adjustableTrailingStop = enabled)
                        "fiveMinuteConfirmation" -> config.enables.copy(fiveMinuteConfirmation = enabled)
                        else -> config.enables
                    }
                    config.copy(enables = enables)
                }
            }
    }

    /** IB adjustable-stop parameters when [enables.adjustableTrailingStop] is on; null when trailing disabled. */
    fun computeAdjustableStop(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double
    ): TouchTurnAdjustableStopParams? {
        if (!enables.adjustableTrailingStop) return null
        if (trailingStopConfigInvalid()) return null
        return TouchTurnAdjustableStop.compute(
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            triggerFraction = trailingStopTriggerFractionOfEntryToTp,
            armFractionOfEntryToStop = trailingStopArmFractionOfEntryToStop
        )
    }

    /** Human-readable reason when trailing config is invalid for representative bracket geometry; null if valid. */
    fun trailingStopValidationError(
        entry: Double = 100.0,
        stopLoss: Double = 95.0,
        takeProfit: Double = 110.0
    ): String? =
        TouchTurnAdjustableStop.validate(
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            triggerFraction = trailingStopTriggerFractionOfEntryToTp,
            armFractionOfEntryToStop = trailingStopArmFractionOfEntryToStop
        )

    /** @deprecated Use [trailingStopValidationError] */
    fun trailingStopFractionsValidationError(): String? = trailingStopValidationError()

    private fun trailingStopConfigInvalid(): Boolean = trailingStopValidationError() != null
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
    val kind: TouchTurnRuleFieldKind,
    val category: TouchTurnRuleCategory,
    /** When false, the field has no single global default (e.g. broker-specific entry offset). */
    val defaultable: Boolean = true
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

/** One-off migration helper for legacy records that still had [openDeadline] enabled before it was configurable. */
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

/** One-off migration helper for legacy in-flight session rules. Prefer [StrategyDeployment.touchTurnRules]. */
fun StrategyDeployment.withNewConfigurableTouchTurnRulesDisabled(): StrategyDeployment {
    val rules = touchTurnRules.withNewConfigurableRulesDisabled()
    val session = touchTurnSession?.let { ctx ->
        val sessionRules = ctx.rules.withNewConfigurableRulesDisabled()
        if (sessionRules == ctx.rules) ctx else ctx.copy(rules = sessionRules)
    }
    if (rules == touchTurnRules && session == touchTurnSession) return this
    return copy(touchTurnRules = rules, touchTurnSession = session)
}
