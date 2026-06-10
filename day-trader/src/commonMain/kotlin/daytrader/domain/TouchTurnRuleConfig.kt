package daytrader.domain

import daytrader.gateway.BrokerKind
import kotlinx.serialization.Serializable

/** Per-deployment enable flags for Touch Turn entry-gate rules ([notDoji] and [openDeadline] off by default). */
@Serializable
data class TouchTurnRuleEnables(
    /** Opening 15m range must meet 25% × 15m ATR(14). */
    val liquidityRange15mAtr: Boolean = true,
    /** Opening 15m range must meet 25% × daily ATR(14) on close (ProReal-style). */
    val liquidityRangeDailyAtr: Boolean = false,
    val notDoji: Boolean = false,
    val volumeExhaustion: Boolean = true,
    val barCloseTurn: Boolean = true,
    val entryWindow: Boolean = true,
    val liveQuoteRequired: Boolean = true,
    val liveBarAgreement: Boolean = true,
    val liveTurnConfirmation: Boolean = true,
    val liveEntryTouchable: Boolean = true,
    val postEntryVolumeBuffer: Boolean = true,
    /** Auto-stop session after [TouchTurnRuleConfig.stopAfterOpenMinutes] from RTH open. */
    val openDeadline: Boolean = false,
    /** SPY vs 200-SMA must align with fade direction (green→bear, red→bull). */
    val macroTrendAlignment: Boolean = false,
    /** Symbol vs 20-SMA must align with fade direction (green→down, red→up). */
    val stockTrendAlignment: Boolean = false
) {
    companion object {
        val DEFAULT: TouchTurnRuleEnables = TouchTurnRuleEnables()
    }
}

fun TouchTurnRuleEnables.requiresLiquidityRange(): Boolean =
    liquidityRange15mAtr || liquidityRangeDailyAtr

/** Full 2M 15m history for 15m ATR liquidity and/or volume-exhaustion SMA. */
fun TouchTurnRuleEnables.requiresDeep15mHistoricalBootstrap(): Boolean =
    liquidityRange15mAtr || volumeExhaustion

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
    /** Opening-bar volume above this multiple of the volume SMA blocks entry (volume exhaustion). */
    val volumeExhaustionRatio: Double = TouchTurnDefaults.VOLUME_EXHAUSTION_RATIO,
    /** 15m periods used to compute ATR for the liquidity threshold. */
    val atrLookbackPeriods: Int = TouchTurnDefaults.ATR_LOOKBACK_PERIODS,
    /** Daily periods used to compute daily ATR for the liquidity threshold. */
    val dailyAtrLookbackPeriods: Int = TouchTurnDefaults.DAILY_ATR_LOOKBACK_PERIODS,
    /** Prior session opening bars used to compute the volume SMA. */
    val volumeSmaPeriods: Int = TouchTurnDefaults.VOLUME_SMA_PERIODS,
    /** Green/short: max position in bar range (0=low, 1=high) for turn confirmation. */
    val closePositionShortMax: Double = TouchTurnDefaults.CLOSE_POSITION_SHORT_MAX,
    /** Red/long: min position in bar range (0=low, 1=high) for turn confirmation. */
    val closePositionLongMin: Double = TouchTurnDefaults.CLOSE_POSITION_LONG_MIN,
    /** Max |bar close − live mid| as a fraction of bar range before IB-live mode rejects the setup. */
    val barLiveDivergenceMaxRatioOfRange: Double =
        TouchTurnDefaults.BAR_LIVE_DIVERGENCE_MAX_RATIO_OF_RANGE,
    /** How far live price may blow through entry before entry is not touchable (fraction of bar range). */
    val entryTouchBufferRatioOfRange: Double = TouchTurnDefaults.ENTRY_TOUCH_BUFFER_RATIO_OF_RANGE,
    /** Nudge entry limit inward from bar extreme — long up from low, short down from high (fraction of bar range). */
    val entryInwardOffsetRatioOfRange: Double = TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE,
    /** Green liquidity bar: take-profit distance as a fraction of bar range. */
    val takeProfitFibRatioGreen: Double = TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN,
    /** Red liquidity bar: take-profit distance as a fraction of bar range. */
    val takeProfitFibRatioRed: Double = TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_RED,
    /** Stop distance = entry-to-target distance ÷ this ratio (reward:risk). Higher = tighter stop. */
    val takeProfitToStopLossRatio: Double = TouchTurnDefaults.TAKE_PROFIT_TO_STOP_LOSS_RATIO,
    /** Max milliseconds after 15m bar close to pass turn confirmation and place entry orders. */
    val closeConfirmationAfterCloseMs: Long = TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS,
    /** Wait after bar end before trusting a closed-bar historical refetch. */
    val closedBarRefetchSettleMs: Long = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS,
    /** Post-entry window: cancel entry if live volume exceeds exhaustion threshold before this elapses. */
    val volumeBufferObservationMs: Long = TouchTurnDefaults.VOLUME_BUFFER_OBSERVATION_MS,
    /** Minutes after RTH open before auto-stop when [TouchTurnRuleEnables.openDeadline] is enabled. */
    val stopAfterOpenMinutes: Int = TouchTurnDefaults.STOP_AFTER_OPEN_MINUTES,
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
                key = "liquidityRange15mAtr",
                label = "Liquidity range (15m ATR)",
                description = "Opening 15m bar range must be at least 25% of 15m ATR(14)."
            ),
            TouchTurnRuleToggleDefinition(
                key = "liquidityRangeDailyAtr",
                label = "Liquidity range (daily ATR)",
                description = "Opening 15m bar range must be at least 25% of daily ATR(14) on close (ProReal-style)."
            ),
            TouchTurnRuleToggleDefinition(
                key = "notDoji",
                label = "Not a doji",
                description = "Bar must be actionable (not a flat doji)."
            ),
            TouchTurnRuleToggleDefinition(
                key = "volumeExhaustion",
                label = "Volume exhaustion",
                description = "Block entry when opening-bar volume exceeds the exhaustion multiple of SMA20."
            ),
            TouchTurnRuleToggleDefinition(
                key = "barCloseTurn",
                label = "Bar close turn",
                description = "15m bar close must confirm the turn zone before entry."
            ),
            TouchTurnRuleToggleDefinition(
                key = "entryWindow",
                label = "Entry window",
                description = "Turn confirmation and bracket placement must complete within the post-close window."
            ),
            TouchTurnRuleToggleDefinition(
                key = "liveQuoteRequired",
                label = "Live quote required",
                description = "IB-live mode: bid and ask must be available before placing entry."
            ),
            TouchTurnRuleToggleDefinition(
                key = "liveBarAgreement",
                label = "Bar / live agreement",
                description = "IB-live mode: live mid must agree with the completed bar close within tolerance."
            ),
            TouchTurnRuleToggleDefinition(
                key = "liveTurnConfirmation",
                label = "Live turn confirmation",
                description = "IB-live mode: live mid must confirm the turn zone on the tape."
            ),
            TouchTurnRuleToggleDefinition(
                key = "liveEntryTouchable",
                label = "Live entry touchable",
                description = "IB-live mode: live price must still be touchable at the entry limit."
            ),
            TouchTurnRuleToggleDefinition(
                key = "postEntryVolumeBuffer",
                label = "Post-entry volume buffer",
                description = "After entry is working, cancel if live volume exceeds exhaustion threshold " +
                    "during the observation window."
            ),
            TouchTurnRuleToggleDefinition(
                key = "openDeadline",
                label = "RTH open deadline",
                description = "Stop the session and flatten working orders/position after the configured maximum " +
                    "minutes from regular-hours open."
            ),
            TouchTurnRuleToggleDefinition(
                key = "macroTrendAlignment",
                label = "Macro trend alignment",
                description = "Only fade when the home-market index matches the opening bar: green short requires bear " +
                    "(below 200-SMA), red long requires bull (above 200-SMA). US→SPY, HK→Hang Seng, UK→FTSE 100."
            ),
            TouchTurnRuleToggleDefinition(
                key = "stockTrendAlignment",
                label = "Stock trend alignment",
                description = "Only fade when the symbol's daily trend matches the opening bar: green short requires " +
                    "downtrend (below 20-SMA), red long requires uptrend (above 20-SMA)."
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
                key = "volumeExhaustionRatio",
                label = "Volume exhaustion (× SMA)",
                description = "If opening-bar volume exceeds this multiple of the volume SMA20, entry is blocked " +
                    "as a high-conviction breakout (volume exhaustion).",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "atrLookbackPeriods",
                label = "ATR lookback (15m bars)",
                description = "Number of prior 15m bars used to compute 15m ATR14 for the liquidity threshold.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "dailyAtrLookbackPeriods",
                label = "ATR lookback (daily bars)",
                description = "Number of prior daily bars used to compute daily ATR(14) for the liquidity threshold.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "volumeSmaPeriods",
                label = "Volume SMA sessions",
                description = "Number of prior session opening 15m bars averaged for volume SMA20 and exhaustion checks.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "closePositionShortMax",
                label = "Short turn zone (max)",
                description = "Green liquidity bar (short): confirming price must sit at or below this fraction of " +
                    "the bar range measured from the low (0 = low, 1 = high). Default 0.45 = lower 45%.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "closePositionLongMin",
                label = "Long turn zone (min)",
                description = "Red liquidity bar (long): confirming price must sit at or above this fraction of " +
                    "the bar range measured from the low. Default 0.55 = upper 45%.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "barLiveDivergenceMaxRatioOfRange",
                label = "Bar / live max gap (× range)",
                description = "IB-live mode: live mid must be within this fraction of bar range of the bar close. " +
                    "Rejects setups when the tape has gapped away from the completed bar.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "entryTouchBufferRatioOfRange",
                label = "Entry touch buffer (× range)",
                description = "IB-live mode: entry limit is not placeable if live price has blown through entry " +
                    "beyond this fraction of bar range (long: ask too far below entry; short: bid too far above).",
                kind = TouchTurnRuleFieldKind.RATIO
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
                key = "closeConfirmationAfterCloseMs",
                label = "Entry window after close (ms)",
                description = "Maximum time after the 15m bar closes to pass turn confirmation and submit bracket " +
                    "orders. After this, entry window expires (no trade).",
                kind = TouchTurnRuleFieldKind.MILLISECONDS
            ),
            TouchTurnRuleFieldDefinition(
                key = "closedBarRefetchSettleMs",
                label = "Closed-bar refetch delay (ms)",
                description = "Milliseconds to wait after bar end before using a refetched historical bar (avoids " +
                    "IB bar-not-final race).",
                kind = TouchTurnRuleFieldKind.MILLISECONDS
            ),
            TouchTurnRuleFieldDefinition(
                key = "volumeBufferObservationMs",
                label = "Post-entry volume buffer (ms)",
                description = "After entry order is working, live volume is watched for this duration. If " +
                    "accumulated volume exceeds the exhaustion threshold, the entry order is cancelled.",
                kind = TouchTurnRuleFieldKind.MILLISECONDS
            ),
            TouchTurnRuleFieldDefinition(
                key = "stopAfterOpenMinutes",
                label = "Max minutes after RTH open",
                description = "When RTH open deadline is enabled, the session auto-stops and flattens broker " +
                    "orders/position this many minutes after the session's RTH open anchor.",
                kind = TouchTurnRuleFieldKind.INTEGER
            )
        )

        fun valueForField(config: TouchTurnRuleConfig, key: String): String = when (key) {
            "atrLiquidityRatio" -> config.atrLiquidityRatio.toString()
            "volumeExhaustionRatio" -> config.volumeExhaustionRatio.toString()
            "atrLookbackPeriods" -> config.atrLookbackPeriods.toString()
            "dailyAtrLookbackPeriods" -> config.dailyAtrLookbackPeriods.toString()
            "volumeSmaPeriods" -> config.volumeSmaPeriods.toString()
            "closePositionShortMax" -> config.closePositionShortMax.toString()
            "closePositionLongMin" -> config.closePositionLongMin.toString()
            "barLiveDivergenceMaxRatioOfRange" -> config.barLiveDivergenceMaxRatioOfRange.toString()
            "entryTouchBufferRatioOfRange" -> config.entryTouchBufferRatioOfRange.toString()
            "entryInwardOffsetRatioOfRange" -> config.entryInwardOffsetRatioOfRange.toString()
            "takeProfitFibRatioGreen" -> config.takeProfitFibRatioGreen.toString()
            "takeProfitFibRatioRed" -> config.takeProfitFibRatioRed.toString()
            "takeProfitToStopLossRatio" -> config.takeProfitToStopLossRatio.toString()
            "closeConfirmationAfterCloseMs" -> config.closeConfirmationAfterCloseMs.toString()
            "closedBarRefetchSettleMs" -> config.closedBarRefetchSettleMs.toString()
            "volumeBufferObservationMs" -> config.volumeBufferObservationMs.toString()
            "stopAfterOpenMinutes" -> config.stopAfterOpenMinutes.toString()
            else -> ""
        }

        fun withFieldValue(config: TouchTurnRuleConfig, key: String, raw: String): TouchTurnRuleConfig? {
            return when (fieldDefinitions.firstOrNull { it.key == key }?.kind) {
                TouchTurnRuleFieldKind.INTEGER -> {
                    val intValue = raw.trim().toIntOrNull() ?: return null
                    if (intValue <= 0) return null
                    when (key) {
                        "atrLookbackPeriods" -> config.copy(atrLookbackPeriods = intValue)
                        "dailyAtrLookbackPeriods" -> config.copy(dailyAtrLookbackPeriods = intValue)
                        "volumeSmaPeriods" -> config.copy(volumeSmaPeriods = intValue)
                        "stopAfterOpenMinutes" -> config.copy(stopAfterOpenMinutes = intValue)
                        else -> null
                    }
                }
                TouchTurnRuleFieldKind.MILLISECONDS -> {
                    val longValue = raw.trim().toLongOrNull() ?: return null
                    if (longValue <= 0L) return null
                    when (key) {
                        "closeConfirmationAfterCloseMs" ->
                            config.copy(closeConfirmationAfterCloseMs = longValue)
                        "closedBarRefetchSettleMs" -> config.copy(closedBarRefetchSettleMs = longValue)
                        "volumeBufferObservationMs" -> config.copy(volumeBufferObservationMs = longValue)
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
                                "volumeExhaustionRatio" -> config.copy(volumeExhaustionRatio = doubleValue)
                                "closePositionShortMax" -> config.copy(closePositionShortMax = doubleValue)
                                "closePositionLongMin" -> config.copy(closePositionLongMin = doubleValue)
                                "barLiveDivergenceMaxRatioOfRange" ->
                                    config.copy(barLiveDivergenceMaxRatioOfRange = doubleValue)
                                "entryTouchBufferRatioOfRange" ->
                                    config.copy(entryTouchBufferRatioOfRange = doubleValue)
                                "takeProfitFibRatioGreen" -> config.copy(takeProfitFibRatioGreen = doubleValue)
                                "takeProfitFibRatioRed" -> config.copy(takeProfitFibRatioRed = doubleValue)
                                "takeProfitToStopLossRatio" ->
                                    config.copy(takeProfitToStopLossRatio = doubleValue)
                                else -> null
                            }
                        }
                    }
                }
                null -> null
            }
        }

        fun isToggleEnabled(config: TouchTurnRuleConfig, key: String): Boolean = when (key) {
            "liquidityRange15mAtr" -> config.enables.liquidityRange15mAtr
            "liquidityRangeDailyAtr" -> config.enables.liquidityRangeDailyAtr
            "notDoji" -> config.enables.notDoji
            "volumeExhaustion" -> config.enables.volumeExhaustion
            "barCloseTurn" -> config.enables.barCloseTurn
            "entryWindow" -> config.enables.entryWindow
            "liveQuoteRequired" -> config.enables.liveQuoteRequired
            "liveBarAgreement" -> config.enables.liveBarAgreement
            "liveTurnConfirmation" -> config.enables.liveTurnConfirmation
            "liveEntryTouchable" -> config.enables.liveEntryTouchable
            "postEntryVolumeBuffer" -> config.enables.postEntryVolumeBuffer
            "openDeadline" -> config.enables.openDeadline
            "macroTrendAlignment" -> config.enables.macroTrendAlignment
            "stockTrendAlignment" -> config.enables.stockTrendAlignment
            else -> true
        }

        fun withToggleEnabled(config: TouchTurnRuleConfig, key: String, enabled: Boolean): TouchTurnRuleConfig {
            val enables = when (key) {
                "liquidityRange15mAtr" -> config.enables.copy(liquidityRange15mAtr = enabled)
                "liquidityRangeDailyAtr" -> config.enables.copy(liquidityRangeDailyAtr = enabled)
                "notDoji" -> config.enables.copy(notDoji = enabled)
                "volumeExhaustion" -> config.enables.copy(volumeExhaustion = enabled)
                "barCloseTurn" -> config.enables.copy(barCloseTurn = enabled)
                "entryWindow" -> config.enables.copy(entryWindow = enabled)
                "liveQuoteRequired" -> config.enables.copy(liveQuoteRequired = enabled)
                "liveBarAgreement" -> config.enables.copy(liveBarAgreement = enabled)
                "liveTurnConfirmation" -> config.enables.copy(liveTurnConfirmation = enabled)
                "liveEntryTouchable" -> config.enables.copy(liveEntryTouchable = enabled)
                "postEntryVolumeBuffer" -> config.enables.copy(postEntryVolumeBuffer = enabled)
                "openDeadline" -> config.enables.copy(openDeadline = enabled)
                "macroTrendAlignment" -> config.enables.copy(macroTrendAlignment = enabled)
                "stockTrendAlignment" -> config.enables.copy(stockTrendAlignment = enabled)
                else -> config.enables
            }
            return config.copy(enables = enables)
        }
    }
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

/** True when bar-close / entry-window / live tape gates should be enforced for this config. */
fun TouchTurnRuleConfig.enforcesCloseConfirmation(requireLivePriceChecks: Boolean): Boolean =
    enables.barCloseTurn ||
        enables.entryWindow ||
        (requireLivePriceChecks && (
            enables.liveTurnConfirmation ||
                enables.liveBarAgreement ||
                enables.liveEntryTouchable
            ))

fun BrokerKind.entryInwardOffsetRatioOfRangeDefault(): Double =
    when (this) {
        BrokerKind.EMULATOR, BrokerKind.REPLAY ->
            TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE_SIMULATED
        BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA ->
            TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE
    }

/** IB and hybrid use daily ATR(14) liquidity; offline emulator/replay keep 15m ATR(14). */
fun BrokerKind.defaultLiquidityRange15mAtrEnabled(): Boolean = when (this) {
    BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> false
    BrokerKind.EMULATOR, BrokerKind.REPLAY -> true
}

fun BrokerKind.defaultLiquidityRangeDailyAtrEnabled(): Boolean = when (this) {
    BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> true
    BrokerKind.EMULATOR, BrokerKind.REPLAY -> false
}

fun TouchTurnRuleConfig.withLiquidityGatesForBrokerKind(kind: BrokerKind): TouchTurnRuleConfig {
    val targetEnables = enables.copy(
        liquidityRange15mAtr = kind.defaultLiquidityRange15mAtrEnabled(),
        liquidityRangeDailyAtr = kind.defaultLiquidityRangeDailyAtrEnabled()
    )
    return if (targetEnables == enables) this else copy(enables = targetEnables)
}

fun TouchTurnRuleConfig.withEntryInwardOffsetForBrokerKind(kind: BrokerKind): TouchTurnRuleConfig {
    val target = kind.entryInwardOffsetRatioOfRangeDefault()
    return if (entryInwardOffsetRatioOfRange == target) this else copy(entryInwardOffsetRatioOfRange = target)
}

/** Ensures legacy-only rule toggles stay off (persisted configs may still have [notDoji] / [openDeadline] enabled). */
fun TouchTurnRuleConfig.withNewConfigurableRulesDisabled(): TouchTurnRuleConfig {
    val normalizedEnables = enables.copy(
        notDoji = false,
        openDeadline = false
    )
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

fun TouchTurnRuleConfig.withDefaultCloseTurnZones(): TouchTurnRuleConfig {
    val targetShort = TouchTurnDefaults.CLOSE_POSITION_SHORT_MAX
    val targetLong = TouchTurnDefaults.CLOSE_POSITION_LONG_MIN
    return if (closePositionShortMax == targetShort && closePositionLongMin == targetLong) {
        this
    } else {
        copy(closePositionShortMax = targetShort, closePositionLongMin = targetLong)
    }
}

fun StrategyDeployment.withDefaultCloseTurnZones(): StrategyDeployment {
    val rules = touchTurnRules.withDefaultCloseTurnZones()
    val session = touchTurnSession?.let { ctx ->
        val patchedRules = ctx.rules.withDefaultCloseTurnZones()
        if (patchedRules == ctx.rules) ctx else ctx.copy(rules = patchedRules)
    }
    val history = sessionHistory.map { day ->
        val record = day.touchTurnRunRecord ?: return@map day
        val runRules = record.rules ?: return@map day
        val patchedRules = runRules.withDefaultCloseTurnZones()
        if (patchedRules == runRules) day else day.copy(touchTurnRunRecord = record.copy(rules = patchedRules))
    }
    if (rules == touchTurnRules && session == touchTurnSession && history == sessionHistory) return this
    return copy(touchTurnRules = rules, touchTurnSession = session, sessionHistory = history)
}

/** Patches deployment and in-flight session rules so new toggles ([notDoji], [openDeadline]) stay disabled. */
fun StrategyDeployment.withNewConfigurableTouchTurnRulesDisabled(): StrategyDeployment {
    val rules = touchTurnRules.withNewConfigurableRulesDisabled()
    val session = touchTurnSession?.let { ctx ->
        val sessionRules = ctx.rules.withNewConfigurableRulesDisabled()
        if (sessionRules == ctx.rules) ctx else ctx.copy(rules = sessionRules)
    }
    if (rules == touchTurnRules && session == touchTurnSession) return this
    return copy(touchTurnRules = rules, touchTurnSession = session)
}
