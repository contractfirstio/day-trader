package daytrader.domain

import daytrader.gateway.BrokerKind
import kotlinx.serialization.Serializable

/** Per-deployment enable flags for Touch Turn rules ([openDeadline] off by default). */
@Serializable
data class TouchTurnRuleEnables(
    /** Opening 15m range must meet ATR × ratio on daily ATR(14). */
    val liquidityRangeDailyAtr: Boolean = false,
    /** Do not place brackets when the opening bar is green and qualifies as a liquidity candle. */
    val skipGreenLiquidityBar: Boolean = false,
    /** Do not place brackets when the opening bar is red and qualifies as a liquidity candle. */
    val skipRedLiquidityBar: Boolean = false,
    /** Apply close position (cp) bounds on liquidity-qualified opening bars. */
    val closePositionGate: Boolean = false,
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
    /** Gates that decide whether orders are placed (liquidity, confirmation, submission checks). */
    TRIGGERS("Triggers", null),
    /** Entry pricing and bracket take-profit / stop-loss geometry. */
    EXECUTION("Execution", null, fieldsAlwaysVisible = true),
    /** Rules applied after entry (trailing stop). */
    POST_ENTRY("Post-entry", "adjustableTrailingStop"),
    /** When the session stops (open deadline). */
    SESSION_LIFECYCLE("Session", "openDeadline"),
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
    /**
     * When [invertTradeSide] is true, nudge the stop entry beyond the bar extreme (green above high,
     * red below low) by this fraction of bar range. 0 = use [entryInwardOffsetRatioOfRange] anchor.
     */
    val entryOutwardOffsetRatioOfRange: Double = TouchTurnDefaults.ENTRY_OUTWARD_OFFSET_RATIO_OF_RANGE,
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
    /** Green liquidity bar: skip when cp is at or below this (inclusive). Null disables. */
    val greenSkipClosePositionBelow: Double? = null,
    /** Green liquidity bar: skip when cp is at or above this (inclusive). Null disables. */
    val greenSkipClosePositionAbove: Double? = null,
    /** Red liquidity bar: skip when cp is at or below this (inclusive). Null disables. */
    val redSkipClosePositionBelow: Double? = null,
    /** Red liquidity bar: skip when cp is at or above this (inclusive). Null disables. */
    val redSkipClosePositionAbove: Double? = null,
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
                label = "Require minimum range (× daily ATR)",
                description = "The closed 15-minute opening bar must meet a minimum range vs daily ATR(14) " +
                    "before brackets are placed.",
                category = TouchTurnRuleCategory.TRIGGERS
            ),
            TouchTurnRuleToggleDefinition(
                key = "fiveMinuteConfirmation",
                label = "5-minute hammer confirmation",
                description = "After a 15m liquidity sweep, wait up to three 5m bars for a hammer that closes " +
                    "inside the sweep range, then enter at market using the original 15m stop and take-profit. " +
                    "Rejected when projected gross profit to the 15m target is below the configured minimum. " +
                    "Available only in Touch Turn (reversal) mode — hidden when invert trade side is on.",
                category = TouchTurnRuleCategory.TRIGGERS
            ),
            TouchTurnRuleToggleDefinition(
                key = "skipGreenLiquidityBar",
                label = "Skip when bar is green",
                description = "Do not place brackets when the closed 15-minute opening bar is green and " +
                    "qualifies as a liquidity bar (range meets the threshold above, if enabled).",
                category = TouchTurnRuleCategory.TRIGGERS
            ),
            TouchTurnRuleToggleDefinition(
                key = "skipRedLiquidityBar",
                label = "Skip when bar is red",
                description = "Do not place brackets when the closed 15-minute opening bar is red and " +
                    "qualifies as a liquidity bar (range meets the threshold above, if enabled).",
                category = TouchTurnRuleCategory.TRIGGERS
            ),
            TouchTurnRuleToggleDefinition(
                key = "closePositionGate",
                label = "Close position (cp) gate",
                description = "Skip liquidity-qualified opening bars when close position is outside the " +
                    "configured bounds for that bar color. Bounds are inclusive at the threshold.",
                category = TouchTurnRuleCategory.TRIGGERS
            ),
            TouchTurnRuleToggleDefinition(
                key = "invertTradeSide",
                label = "Invert trade side (continuation)",
                description = "Same entry levels as Touch and Turn, but long where reversal would short and vice " +
                    "versa. Uses a stop entry on breakout instead of a resting limit.",
                category = TouchTurnRuleCategory.EXECUTION
            ),
            TouchTurnRuleToggleDefinition(
                key = "adjustableTrailingStop",
                label = "Adjustable trailing stop",
                description = "After price reaches the trail-arm level, move the stop toward the initial stop " +
                    "(by the configured entry-to-stop fraction) and ratchet it up (long) or down (short) as price " +
                    "continues in your favor.",
                category = TouchTurnRuleCategory.POST_ENTRY
            ),
            TouchTurnRuleToggleDefinition(
                key = "openDeadline",
                label = "RTH open deadline",
                description = "Stop the session and flatten working orders/position after the configured maximum " +
                    "minutes from regular-hours open.",
                category = TouchTurnRuleCategory.SESSION_LIFECYCLE
            ),
        )

        val fieldDefinitions: List<TouchTurnRuleFieldDefinition> = listOf(
            TouchTurnRuleFieldDefinition(
                key = "atrLiquidityRatio",
                label = "Liquidity range (× ATR)",
                description = "Opening 15m bar range must be at least this multiple of ATR14 to count as a " +
                    "liquidity candle. Higher = stricter (fewer trades).",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.LIQUIDITY_THRESHOLD,
                defaultable = true,
                visibleWhenToggleKey = "liquidityRangeDailyAtr"
            ),
            TouchTurnRuleFieldDefinition(
                key = "dailyAtrLookbackPeriods",
                label = "ATR lookback (daily bars)",
                description = "Number of prior daily bars used to compute daily ATR(14) for the liquidity threshold.",
                kind = TouchTurnRuleFieldKind.INTEGER,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.LIQUIDITY_THRESHOLD,
                defaultable = true,
                visibleWhenToggleKey = "liquidityRangeDailyAtr"
            ),
            TouchTurnRuleFieldDefinition(
                key = "greenSkipClosePositionBelow",
                label = "Green bar — skip if cp at or below",
                description = "Skip green liquidity opening bars when close position (cp) is at or below this " +
                    "value (0 = bar closed at low, 1 = at high). Leave empty to disable.",
                kind = TouchTurnRuleFieldKind.OPTIONAL_RATIO,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.OPENING_BAR_CLOSE_POSITION,
                defaultable = true,
                visibleWhenToggleKey = "closePositionGate"
            ),
            TouchTurnRuleFieldDefinition(
                key = "greenSkipClosePositionAbove",
                label = "Green bar — skip if cp at or above",
                description = "Skip green liquidity opening bars when cp is at or above this value. Leave empty " +
                    "to disable.",
                kind = TouchTurnRuleFieldKind.OPTIONAL_RATIO,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.OPENING_BAR_CLOSE_POSITION,
                defaultable = true,
                visibleWhenToggleKey = "closePositionGate"
            ),
            TouchTurnRuleFieldDefinition(
                key = "redSkipClosePositionBelow",
                label = "Red bar — skip if cp at or below",
                description = "Skip red liquidity opening bars when cp is at or below this value. Leave empty " +
                    "to disable.",
                kind = TouchTurnRuleFieldKind.OPTIONAL_RATIO,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.OPENING_BAR_CLOSE_POSITION,
                defaultable = true,
                visibleWhenToggleKey = "closePositionGate"
            ),
            TouchTurnRuleFieldDefinition(
                key = "redSkipClosePositionAbove",
                label = "Red bar — skip if cp at or above",
                description = "Skip red liquidity opening bars when cp is at or above this value. Leave empty " +
                    "to disable.",
                kind = TouchTurnRuleFieldKind.OPTIONAL_RATIO,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.OPENING_BAR_CLOSE_POSITION,
                defaultable = true,
                visibleWhenToggleKey = "closePositionGate"
            ),
            TouchTurnRuleFieldDefinition(
                key = "minGrossProfit",
                label = "Min gross profit",
                description = "Before submitting a bracket, require projected gross profit to the " +
                    "take-profit (|TP − entry| × quantity) to be at least this amount in the symbol's " +
                    "currency. Applies to the default 15m entry and to 5m hammer-confirmed market entry. " +
                    "0 disables the gate.",
                kind = TouchTurnRuleFieldKind.PRICE,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.SUBMISSION_GATES,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "closedBarRefetchSettleMs",
                label = "Closed-bar refetch settle (ms)",
                description = "Wait after the 15m bar ends before trusting a post-close IB historical refetch " +
                    "for liquidity evaluation.",
                kind = TouchTurnRuleFieldKind.MILLISECONDS,
                category = TouchTurnRuleCategory.TRIGGERS,
                subGroup = TouchTurnRuleFieldSubGroup.BAR_TIMING,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "entryInwardOffsetRatioOfRange",
                label = "Entry inward offset (× range)",
                description = "Nudge the entry toward the bar middle: long up from bar low, short down from bar high. " +
                    "0 = bar extreme. Used for reversal limits and as the inverse anchor when outward offset is 0. " +
                    "Depends on broker mode — not reset by the global defaults button.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.EXECUTION,
                subGroup = TouchTurnRuleFieldSubGroup.REVERSAL_ENTRY,
                defaultable = false
            ),
            TouchTurnRuleFieldDefinition(
                key = "entryOutwardOffsetRatioOfRange",
                label = "Entry outward offset (× range)",
                description = "Place the stop entry beyond the bar high/low by this fraction of range so price " +
                    "must break the opening extreme before entry. 0 uses the inward offset level above.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.EXECUTION,
                subGroup = TouchTurnRuleFieldSubGroup.INVERT_ENTRY,
                defaultable = false,
                visibleWhenInvertTradeSide = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitFibRatioGreen",
                label = "Take profit — green bar (× range)",
                description = "Green opening bar: take-profit distance as a fraction of bar range. " +
                    "Reversal shorts at the high; inverse longs on breakout.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.EXECUTION,
                subGroup = TouchTurnRuleFieldSubGroup.TAKE_PROFIT_AND_RISK,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitFibRatioRed",
                label = "Take profit — red bar (× range)",
                description = "Red opening bar: take-profit distance as a fraction of bar range. " +
                    "Reversal longs at the low; inverse shorts on breakdown.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.EXECUTION,
                subGroup = TouchTurnRuleFieldSubGroup.TAKE_PROFIT_AND_RISK,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "takeProfitToStopLossRatio",
                label = "Take profit : stop loss ratio",
                description = "Stop distance is entry-to-target distance divided by this ratio. Default 2 = stop at " +
                    "half the take-profit distance (2:1 reward:risk). Higher values tighten the stop (smaller loss).",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.EXECUTION,
                subGroup = TouchTurnRuleFieldSubGroup.TAKE_PROFIT_AND_RISK,
                defaultable = true
            ),
            TouchTurnRuleFieldDefinition(
                key = "trailingStopTriggerFractionOfEntryToTp",
                label = "Trail arm (fraction to TP)",
                description = "When adjustable trailing is enabled, price must reach entry plus this fraction of " +
                    "the entry-to-take-profit distance before the stop moves to the arm level and begins trailing. " +
                    "Default 0.5 = halfway to target. Must be between 0 and 1.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.POST_ENTRY,
                subGroup = TouchTurnRuleFieldSubGroup.TRAILING_WHEN_ON,
                defaultable = true,
                visibleWhenToggleKey = "adjustableTrailingStop"
            ),
            TouchTurnRuleFieldDefinition(
                key = "trailingStopArmFractionOfEntryToStop",
                label = "Trail arm (fraction to stop)",
                description = "When trailing activates, place the first trailing stop this fraction of the way " +
                    "from entry toward the initial stop. 0 = at entry (breakeven). 0.5 = halfway between entry and " +
                    "stop. 1 = unchanged at the initial stop. Must be between 0 and 1.",
                kind = TouchTurnRuleFieldKind.RATIO,
                category = TouchTurnRuleCategory.POST_ENTRY,
                subGroup = TouchTurnRuleFieldSubGroup.TRAILING_WHEN_ON,
                defaultable = true,
                visibleWhenToggleKey = "adjustableTrailingStop"
            ),
            TouchTurnRuleFieldDefinition(
                key = "stopAfterOpenMinutes",
                label = "Max minutes after RTH open",
                description = "When RTH open deadline is enabled, the session auto-stops and flattens broker " +
                    "orders/position this many minutes after the session's RTH open anchor.",
                kind = TouchTurnRuleFieldKind.INTEGER,
                category = TouchTurnRuleCategory.SESSION_LIFECYCLE,
                subGroup = TouchTurnRuleFieldSubGroup.DEADLINE_WHEN_ON,
                defaultable = true,
                visibleWhenToggleKey = "openDeadline"
            ),
        )

        fun fieldsForCategory(category: TouchTurnRuleCategory): List<TouchTurnRuleFieldDefinition> =
            fieldDefinitions.filter { it.category == category }

        /** Defaultable thresholds first, then mandatory fields without a global default. */
        fun fieldsForCategoryDisplay(
            category: TouchTurnRuleCategory,
            invertTradeSide: Boolean = false,
            toggleValues: Map<String, Boolean> = emptyMap()
        ): List<TouchTurnRuleFieldDefinition> =
            fieldsForCategory(category)
                .filter { it.isVisibleForConfig(invertTradeSide, toggleValues) }
                .sortedWith(
                    compareByDescending<TouchTurnRuleFieldDefinition> { it.defaultable }.thenBy { it.label }
                )

        fun fieldGroupsForCategory(
            category: TouchTurnRuleCategory,
            invertTradeSide: Boolean,
            toggleValues: Map<String, Boolean>
        ): List<TouchTurnRuleFieldGroup> {
            val visible = fieldsForCategory(category)
                .filter { it.isVisibleForConfig(invertTradeSide, toggleValues) }
            if (visible.isEmpty()) return emptyList()
            return when (category) {
                TouchTurnRuleCategory.TRIGGERS -> listOfNotNull(
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.LIQUIDITY_THRESHOLD),
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.OPENING_BAR_CLOSE_POSITION),
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.SUBMISSION_GATES),
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.BAR_TIMING)
                )
                TouchTurnRuleCategory.EXECUTION -> listOfNotNull(
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.REVERSAL_ENTRY),
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.INVERT_ENTRY),
                    visible.groupBySubGroup(TouchTurnRuleFieldSubGroup.TAKE_PROFIT_AND_RISK)
                )
                else -> visible
                    .groupBy { it.subGroup }
                    .entries
                    .sortedBy { (subGroup, _) -> subGroup?.ordinal ?: Int.MAX_VALUE }
                    .mapNotNull { (subGroup, fields) ->
                        if (fields.isEmpty()) return@mapNotNull null
                        TouchTurnRuleFieldGroup(
                            label = subGroup?.label ?: category.label,
                            fields = fields.sortedBy { it.label },
                            testTagSuffix = subGroup?.name?.lowercase() ?: category.name.lowercase()
                        )
                    }
            }
        }

        fun visibleFieldDefinitions(
            invertTradeSide: Boolean,
            toggleValues: Map<String, Boolean> = emptyMap()
        ): List<TouchTurnRuleFieldDefinition> =
            fieldDefinitions.filter { it.isVisibleForConfig(invertTradeSide, toggleValues) }

        fun invertLinkedFieldDefinitions(invertTradeSide: Boolean): List<TouchTurnRuleFieldDefinition> =
            fieldsForCategoryDisplay(TouchTurnRuleCategory.EXECUTION, invertTradeSide = invertTradeSide)
                .filter { it.subGroup == TouchTurnRuleFieldSubGroup.INVERT_ENTRY }

        fun togglesForCategory(category: TouchTurnRuleCategory): List<TouchTurnRuleToggleDefinition> =
            toggleDefinitions.filter { it.category == category }

        private fun List<TouchTurnRuleFieldDefinition>.groupBySubGroup(
            subGroup: TouchTurnRuleFieldSubGroup
        ): TouchTurnRuleFieldGroup? {
            val fields = filter { it.subGroup == subGroup }.sortedBy { it.label }
            if (fields.isEmpty()) return null
            return TouchTurnRuleFieldGroup(
                label = subGroup.label,
                fields = fields,
                testTagSuffix = subGroup.name.lowercase()
            )
        }

        fun toggleForCategory(category: TouchTurnRuleCategory): TouchTurnRuleToggleDefinition? =
            category.toggleKey?.let { key -> toggleDefinitions.find { it.key == key } }

        fun valueForField(config: TouchTurnRuleConfig, key: String): String = when (key) {
            "atrLiquidityRatio" -> config.atrLiquidityRatio.toString()
            "dailyAtrLookbackPeriods" -> config.dailyAtrLookbackPeriods.toString()
            "entryInwardOffsetRatioOfRange" -> config.entryInwardOffsetRatioOfRange.toString()
            "entryOutwardOffsetRatioOfRange" -> config.entryOutwardOffsetRatioOfRange.toString()
            "takeProfitFibRatioGreen" -> config.takeProfitFibRatioGreen.toString()
            "takeProfitFibRatioRed" -> config.takeProfitFibRatioRed.toString()
            "takeProfitToStopLossRatio" -> config.takeProfitToStopLossRatio.toString()
            "stopAfterOpenMinutes" -> config.stopAfterOpenMinutes.toString()
            "trailingStopTriggerFractionOfEntryToTp" ->
                config.trailingStopTriggerFractionOfEntryToTp.toString()
            "trailingStopArmFractionOfEntryToStop" ->
                config.trailingStopArmFractionOfEntryToStop.toString()
            "minGrossProfit" -> config.minGrossProfit.toString()
            "closedBarRefetchSettleMs" -> config.closedBarRefetchSettleMs.toString()
            "greenSkipClosePositionBelow" -> config.greenSkipClosePositionBelow?.toString().orEmpty()
            "greenSkipClosePositionAbove" -> config.greenSkipClosePositionAbove?.toString().orEmpty()
            "redSkipClosePositionBelow" -> config.redSkipClosePositionBelow?.toString().orEmpty()
            "redSkipClosePositionAbove" -> config.redSkipClosePositionAbove?.toString().orEmpty()
            else -> ""
        }

        private fun parseOptionalClosePositionThreshold(raw: String): Double? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val value = trimmed.toDoubleOrNull() ?: return null
            if (value < 0.0 || value > 1.0) return null
            return value
        }

        private fun withOptionalClosePositionField(
            config: TouchTurnRuleConfig,
            key: String,
            raw: String
        ): TouchTurnRuleConfig? {
            val value = parseOptionalClosePositionThreshold(raw) ?: return if (raw.trim().isEmpty()) {
                when (key) {
                    "greenSkipClosePositionBelow" -> config.copy(greenSkipClosePositionBelow = null)
                    "greenSkipClosePositionAbove" -> config.copy(greenSkipClosePositionAbove = null)
                    "redSkipClosePositionBelow" -> config.copy(redSkipClosePositionBelow = null)
                    "redSkipClosePositionAbove" -> config.copy(redSkipClosePositionAbove = null)
                    else -> null
                }
            } else {
                null
            }
            return when (key) {
                "greenSkipClosePositionBelow" -> config.copy(greenSkipClosePositionBelow = value)
                "greenSkipClosePositionAbove" -> config.copy(greenSkipClosePositionAbove = value)
                "redSkipClosePositionBelow" -> config.copy(redSkipClosePositionBelow = value)
                "redSkipClosePositionAbove" -> config.copy(redSkipClosePositionAbove = value)
                else -> null
            }
        }

        fun withFieldValue(config: TouchTurnRuleConfig, key: String, raw: String): TouchTurnRuleConfig? {
            fieldDefinitions.firstOrNull { it.key == key && it.kind == TouchTurnRuleFieldKind.OPTIONAL_RATIO }
                ?.let { return withOptionalClosePositionField(config, key, raw) }
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
                TouchTurnRuleFieldKind.MILLISECONDS -> {
                    val longValue = raw.trim().toLongOrNull() ?: return null
                    if (longValue <= 0L) return null
                    when (key) {
                        "closedBarRefetchSettleMs" -> config.copy(closedBarRefetchSettleMs = longValue)
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
                        "entryOutwardOffsetRatioOfRange" -> {
                            if (doubleValue < 0.0 ||
                                doubleValue > TouchTurnDefaults.ENTRY_OUTWARD_OFFSET_RATIO_OF_RANGE_MAX
                            ) {
                                return null
                            }
                            config.copy(entryOutwardOffsetRatioOfRange = doubleValue)
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
            "skipGreenLiquidityBar" -> config.enables.skipGreenLiquidityBar
            "skipRedLiquidityBar" -> config.enables.skipRedLiquidityBar
            "closePositionGate" -> config.enables.closePositionGate
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
                        "skipGreenLiquidityBar" -> config.enables.copy(skipGreenLiquidityBar = enabled)
                        "skipRedLiquidityBar" -> config.enables.copy(skipRedLiquidityBar = enabled)
                        "closePositionGate" -> config.enables.copy(closePositionGate = enabled)
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
enum class TouchTurnRuleFieldSubGroup(val label: String) {
    LIQUIDITY_THRESHOLD("15m bar range threshold"),
    OPENING_BAR_CLOSE_POSITION("Close position (cp)"),
    SUBMISSION_GATES("Submission gates"),
    BAR_TIMING("15m bar close timing"),
    REVERSAL_ENTRY("Reversal / default entry"),
    INVERT_ENTRY("When inverse is on"),
    TAKE_PROFIT_AND_RISK("Take profit & risk"),
    TRAILING_WHEN_ON("When trailing is on"),
    DEADLINE_WHEN_ON("When deadline is on"),
}

@Serializable
data class TouchTurnRuleFieldGroup(
    val label: String,
    val fields: List<TouchTurnRuleFieldDefinition>,
    val testTagSuffix: String
)

@Serializable
enum class TouchTurnRuleFieldKind {
    RATIO,
    OPTIONAL_RATIO,
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
    val subGroup: TouchTurnRuleFieldSubGroup? = null,
    /** When false, the field has no single global default (e.g. broker-specific entry offset). */
    val defaultable: Boolean = true,
    /** When non-null, field is shown only when [invertTradeSide] matches this value. */
    val visibleWhenInvertTradeSide: Boolean? = null,
    /** When non-null, field is shown only when the named toggle is enabled. */
    val visibleWhenToggleKey: String? = null
)

fun TouchTurnRuleFieldDefinition.isVisibleForInvertTradeSide(invertTradeSide: Boolean): Boolean =
    visibleWhenInvertTradeSide?.let { it == invertTradeSide } != false

fun TouchTurnRuleFieldDefinition.isVisibleForToggle(toggleValues: Map<String, Boolean>): Boolean =
    visibleWhenToggleKey?.let { toggleValues[it] == true } != false

fun TouchTurnRuleFieldDefinition.isVisibleForConfig(
    invertTradeSide: Boolean,
    toggleValues: Map<String, Boolean>
): Boolean = isVisibleForInvertTradeSide(invertTradeSide) && isVisibleForToggle(toggleValues)

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
