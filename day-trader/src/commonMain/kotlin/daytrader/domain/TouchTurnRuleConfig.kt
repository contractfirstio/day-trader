package daytrader.domain

import kotlinx.serialization.Serializable

/** Per-deployment enable flags for Touch Turn entry-gate rules (all enabled by default). */
@Serializable
data class TouchTurnRuleEnables(
    val liquidityRange: Boolean = true,
    val notDoji: Boolean = true,
    val volumeExhaustion: Boolean = true,
    val barCloseTurn: Boolean = true,
    val entryWindow: Boolean = true,
    val liveQuoteRequired: Boolean = true,
    val liveBarAgreement: Boolean = true,
    val liveTurnConfirmation: Boolean = true,
    val liveEntryTouchable: Boolean = true,
    val postEntryVolumeBuffer: Boolean = true
) {
    companion object {
        val DEFAULT: TouchTurnRuleEnables = TouchTurnRuleEnables()
    }
}

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
    /** Prior session opening bars used to compute the volume SMA. */
    val volumeSmaPeriods: Int = TouchTurnDefaults.VOLUME_SMA_PERIODS,
    /** Min distance from entry as a fraction of bar range for turn confirmation (bar close and live mid). */
    val closeConfirmationMinDistanceRatioOfRange: Double =
        TouchTurnDefaults.CLOSE_CONFIRMATION_MIN_DISTANCE_RATIO_OF_RANGE,
    /** Green/short: max position in bar range (0=low, 1=high) for turn confirmation. */
    val closePositionShortMax: Double = TouchTurnDefaults.CLOSE_POSITION_SHORT_MAX,
    /** Red/long: min position in bar range (0=low, 1=high) for turn confirmation. */
    val closePositionLongMin: Double = TouchTurnDefaults.CLOSE_POSITION_LONG_MIN,
    /** Max |bar close − live mid| as a fraction of bar range before IB-live mode rejects the setup. */
    val barLiveDivergenceMaxRatioOfRange: Double =
        TouchTurnDefaults.BAR_LIVE_DIVERGENCE_MAX_RATIO_OF_RANGE,
    /** How far live price may blow through entry before entry is not touchable (fraction of bar range). */
    val entryTouchBufferRatioOfRange: Double = TouchTurnDefaults.ENTRY_TOUCH_BUFFER_RATIO_OF_RANGE,
    /** Minimum absolute entry-to-stop distance (spread / noise floor). */
    val minStopDistance: Double = TouchTurnDefaults.MIN_STOP_DISTANCE,
    /** Green liquidity bar: take-profit distance as a fraction of bar range. */
    val takeProfitFibRatioGreen: Double = TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN,
    /** Red liquidity bar: take-profit distance as a fraction of bar range. */
    val takeProfitFibRatioRed: Double = TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_RED,
    /** Max milliseconds after 15m bar close to pass turn confirmation and place entry orders. */
    val closeConfirmationAfterCloseMs: Long = TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS,
    /** Wait after bar end before trusting a closed-bar historical refetch. */
    val closedBarRefetchSettleMs: Long = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS,
    /** Post-entry window: cancel entry if live volume exceeds exhaustion threshold before this elapses. */
    val volumeBufferObservationMs: Long = TouchTurnDefaults.VOLUME_BUFFER_OBSERVATION_MS,
    /** Which entry-gate rules are enforced for this deployment. */
    val enables: TouchTurnRuleEnables = TouchTurnRuleEnables.DEFAULT
) {
    companion object {
        val DEFAULT: TouchTurnRuleConfig = TouchTurnRuleConfig()

        val toggleDefinitions: List<TouchTurnRuleToggleDefinition> = listOf(
            TouchTurnRuleToggleDefinition(
                key = "liquidityRange",
                label = "Liquidity range",
                description = "Opening 15m bar range must meet the ATR liquidity threshold."
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
                label = "ATR lookback (bars)",
                description = "Number of prior 15m bars used to compute ATR14, which feeds the liquidity threshold.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "volumeSmaPeriods",
                label = "Volume SMA sessions",
                description = "Number of prior session opening 15m bars averaged for volume SMA20 and exhaustion checks.",
                kind = TouchTurnRuleFieldKind.INTEGER
            ),
            TouchTurnRuleFieldDefinition(
                key = "closeConfirmationMinDistanceRatioOfRange",
                label = "Turn separation (× range)",
                description = "For turn confirmation, price must be at least this fraction of the bar range away " +
                    "from entry on the confirming side (green/short: below entry; red/long: above entry).",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "closePositionShortMax",
                label = "Short turn zone (max)",
                description = "Green liquidity bar (short): confirming price must sit at or below this fraction of " +
                    "the bar range measured from the low (0 = low, 1 = high). Default 0.35 = lower third.",
                kind = TouchTurnRuleFieldKind.RATIO
            ),
            TouchTurnRuleFieldDefinition(
                key = "closePositionLongMin",
                label = "Long turn zone (min)",
                description = "Red liquidity bar (long): confirming price must sit at or above this fraction of " +
                    "the bar range measured from the low. Default 0.65 = upper third.",
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
                key = "minStopDistance",
                label = "Min stop distance",
                description = "Minimum absolute distance from entry to stop loss when building the bracket (noise / " +
                    "spread floor). Stop is at least half the entry-to-TP distance or this value, whichever is larger.",
                kind = TouchTurnRuleFieldKind.PRICE
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
            )
        )

        fun valueForField(config: TouchTurnRuleConfig, key: String): String = when (key) {
            "atrLiquidityRatio" -> config.atrLiquidityRatio.toString()
            "volumeExhaustionRatio" -> config.volumeExhaustionRatio.toString()
            "atrLookbackPeriods" -> config.atrLookbackPeriods.toString()
            "volumeSmaPeriods" -> config.volumeSmaPeriods.toString()
            "closeConfirmationMinDistanceRatioOfRange" ->
                config.closeConfirmationMinDistanceRatioOfRange.toString()
            "closePositionShortMax" -> config.closePositionShortMax.toString()
            "closePositionLongMin" -> config.closePositionLongMin.toString()
            "barLiveDivergenceMaxRatioOfRange" -> config.barLiveDivergenceMaxRatioOfRange.toString()
            "entryTouchBufferRatioOfRange" -> config.entryTouchBufferRatioOfRange.toString()
            "minStopDistance" -> config.minStopDistance.toString()
            "takeProfitFibRatioGreen" -> config.takeProfitFibRatioGreen.toString()
            "takeProfitFibRatioRed" -> config.takeProfitFibRatioRed.toString()
            "closeConfirmationAfterCloseMs" -> config.closeConfirmationAfterCloseMs.toString()
            "closedBarRefetchSettleMs" -> config.closedBarRefetchSettleMs.toString()
            "volumeBufferObservationMs" -> config.volumeBufferObservationMs.toString()
            else -> ""
        }

        fun withFieldValue(config: TouchTurnRuleConfig, key: String, raw: String): TouchTurnRuleConfig? {
            return when (fieldDefinitions.firstOrNull { it.key == key }?.kind) {
                TouchTurnRuleFieldKind.INTEGER -> {
                    val intValue = raw.trim().toIntOrNull() ?: return null
                    if (intValue <= 0) return null
                    when (key) {
                        "atrLookbackPeriods" -> config.copy(atrLookbackPeriods = intValue)
                        "volumeSmaPeriods" -> config.copy(volumeSmaPeriods = intValue)
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
                    if (doubleValue <= 0.0) return null
                    when (key) {
                        "atrLiquidityRatio" -> config.copy(atrLiquidityRatio = doubleValue)
                        "volumeExhaustionRatio" -> config.copy(volumeExhaustionRatio = doubleValue)
                        "closeConfirmationMinDistanceRatioOfRange" ->
                            config.copy(closeConfirmationMinDistanceRatioOfRange = doubleValue)
                        "closePositionShortMax" -> config.copy(closePositionShortMax = doubleValue)
                        "closePositionLongMin" -> config.copy(closePositionLongMin = doubleValue)
                        "barLiveDivergenceMaxRatioOfRange" ->
                            config.copy(barLiveDivergenceMaxRatioOfRange = doubleValue)
                        "entryTouchBufferRatioOfRange" -> config.copy(entryTouchBufferRatioOfRange = doubleValue)
                        "minStopDistance" -> config.copy(minStopDistance = doubleValue)
                        "takeProfitFibRatioGreen" -> config.copy(takeProfitFibRatioGreen = doubleValue)
                        "takeProfitFibRatioRed" -> config.copy(takeProfitFibRatioRed = doubleValue)
                        else -> null
                    }
                }
                null -> null
            }
        }

        fun isToggleEnabled(config: TouchTurnRuleConfig, key: String): Boolean = when (key) {
            "liquidityRange" -> config.enables.liquidityRange
            "notDoji" -> config.enables.notDoji
            "volumeExhaustion" -> config.enables.volumeExhaustion
            "barCloseTurn" -> config.enables.barCloseTurn
            "entryWindow" -> config.enables.entryWindow
            "liveQuoteRequired" -> config.enables.liveQuoteRequired
            "liveBarAgreement" -> config.enables.liveBarAgreement
            "liveTurnConfirmation" -> config.enables.liveTurnConfirmation
            "liveEntryTouchable" -> config.enables.liveEntryTouchable
            "postEntryVolumeBuffer" -> config.enables.postEntryVolumeBuffer
            else -> true
        }

        fun withToggleEnabled(config: TouchTurnRuleConfig, key: String, enabled: Boolean): TouchTurnRuleConfig {
            val enables = when (key) {
                "liquidityRange" -> config.enables.copy(liquidityRange = enabled)
                "notDoji" -> config.enables.copy(notDoji = enabled)
                "volumeExhaustion" -> config.enables.copy(volumeExhaustion = enabled)
                "barCloseTurn" -> config.enables.copy(barCloseTurn = enabled)
                "entryWindow" -> config.enables.copy(entryWindow = enabled)
                "liveQuoteRequired" -> config.enables.copy(liveQuoteRequired = enabled)
                "liveBarAgreement" -> config.enables.copy(liveBarAgreement = enabled)
                "liveTurnConfirmation" -> config.enables.copy(liveTurnConfirmation = enabled)
                "liveEntryTouchable" -> config.enables.copy(liveEntryTouchable = enabled)
                "postEntryVolumeBuffer" -> config.enables.copy(postEntryVolumeBuffer = enabled)
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

fun StrategyDeployment.effectiveTouchTurnRules(): TouchTurnRuleConfig = touchTurnRules
