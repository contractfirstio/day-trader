package daytrader.data.persistence

import daytrader.domain.TouchTurnClosePositionTriggerMode
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables

object TouchTurnRuleConfigPersistence {
    fun toDomain(record: TouchTurnRuleConfigRecord?): TouchTurnRuleConfig =
        record?.let {
            val legacyRedBelow = parseClosePositionTriggerMode(it.redCapitulationBarShapeTrigger)
            val legacyGreenAbove = parseClosePositionTriggerMode(it.greenEuphoriaBarShapeTrigger)
            TouchTurnRuleConfig(
                atrLiquidityRatio = it.atrLiquidityRatio,
                dailyAtrLookbackPeriods = it.dailyAtrLookbackPeriods,
                entryInwardOffsetRatioOfRange = it.entryInwardOffsetRatioOfRange,
                entryOutwardOffsetRatioOfRange = it.entryOutwardOffsetRatioOfRange,
                takeProfitFibRatioGreen = it.takeProfitFibRatioGreen,
                takeProfitFibRatioRed = it.takeProfitFibRatioRed,
                takeProfitToStopLossRatio = it.takeProfitToStopLossRatio,
                closedBarRefetchSettleMs = it.closedBarRefetchSettleMs,
                stopAfterOpenMinutes = it.stopAfterOpenMinutes,
                trailingStopTriggerFractionOfEntryToTp = it.trailingStopTriggerFractionOfEntryToTp,
                trailingStopArmFractionOfEntryToStop = it.trailingStopArmFractionOfEntryToStop,
                enables = TouchTurnRuleEnables(
                    liquidityRangeDailyAtr = it.enableLiquidityRangeDailyAtr,
                    closePositionGate = it.enableClosePositionGate,
                    openDeadline = it.enableOpenDeadline,
                    adjustableTrailingStop = it.enableAdjustableTrailingStop,
                    fiveMinuteConfirmation = it.enableFiveMinuteConfirmation
                ),
                invertTradeSide = it.invertTradeSide,
                minGrossProfit = it.minGrossProfit,
                greenSkipClosePositionBelow = it.greenSkipClosePositionBelow,
                greenSkipClosePositionAbove = it.greenSkipClosePositionAbove,
                redSkipClosePositionBelow = it.redSkipClosePositionBelow,
                redSkipClosePositionAbove = it.redSkipClosePositionAbove,
                greenClosePositionBelowAction = parseClosePositionTriggerMode(it.greenClosePositionBelowAction),
                greenClosePositionAboveAction = parseClosePositionTriggerMode(it.greenClosePositionAboveAction),
                redClosePositionBelowAction = parseClosePositionTriggerMode(it.redClosePositionBelowAction),
                redClosePositionAboveAction = parseClosePositionTriggerMode(it.redClosePositionAboveAction),
                greenLiquidityBarAction = parseClosePositionTriggerMode(it.greenLiquidityBarAction),
                redLiquidityBarAction = parseClosePositionTriggerMode(it.redLiquidityBarAction)
            ).let { config ->
                migrateLegacyBarShapeTriggers(config, legacyRedBelow, legacyGreenAbove)
                    .let { migrated -> migrateLegacyColorSkipFlags(migrated, it.enableSkipGreenLiquidityBar, it.enableSkipRedLiquidityBar) }
            }
        } ?: TouchTurnRuleConfig.DEFAULT

    private fun parseClosePositionTriggerMode(raw: String): TouchTurnClosePositionTriggerMode =
        runCatching { TouchTurnClosePositionTriggerMode.valueOf(raw) }
            .getOrDefault(TouchTurnClosePositionTriggerMode.OFF)

    private fun migrateLegacyBarShapeTriggers(
        config: TouchTurnRuleConfig,
        legacyRedBelow: TouchTurnClosePositionTriggerMode,
        legacyGreenAbove: TouchTurnClosePositionTriggerMode
    ): TouchTurnRuleConfig {
        var migrated = config
        if (config.redClosePositionBelowAction == TouchTurnClosePositionTriggerMode.OFF &&
            legacyRedBelow != TouchTurnClosePositionTriggerMode.OFF
        ) {
            migrated = migrated.copy(
                redClosePositionBelowAction = legacyRedBelow,
                redSkipClosePositionBelow = migrated.redSkipClosePositionBelow ?: 0.15
            )
        }
        if (config.greenClosePositionAboveAction == TouchTurnClosePositionTriggerMode.OFF &&
            legacyGreenAbove != TouchTurnClosePositionTriggerMode.OFF
        ) {
            migrated = migrated.copy(
                greenClosePositionAboveAction = legacyGreenAbove,
                greenSkipClosePositionAbove = migrated.greenSkipClosePositionAbove ?: 0.85
            )
        }
        return migrated
    }

    private fun migrateLegacyColorSkipFlags(
        config: TouchTurnRuleConfig,
        legacySkipGreen: Boolean,
        legacySkipRed: Boolean
    ): TouchTurnRuleConfig {
        var migrated = config
        if (config.greenLiquidityBarAction == TouchTurnClosePositionTriggerMode.OFF && legacySkipGreen) {
            migrated = migrated.copy(greenLiquidityBarAction = TouchTurnClosePositionTriggerMode.SKIP)
        }
        if (config.redLiquidityBarAction == TouchTurnClosePositionTriggerMode.OFF && legacySkipRed) {
            migrated = migrated.copy(redLiquidityBarAction = TouchTurnClosePositionTriggerMode.SKIP)
        }
        return migrated
    }

    fun toRecord(config: TouchTurnRuleConfig): TouchTurnRuleConfigRecord =
        TouchTurnRuleConfigRecord(
            atrLiquidityRatio = config.atrLiquidityRatio,
            dailyAtrLookbackPeriods = config.dailyAtrLookbackPeriods,
            entryInwardOffsetRatioOfRange = config.entryInwardOffsetRatioOfRange,
            entryOutwardOffsetRatioOfRange = config.entryOutwardOffsetRatioOfRange,
            takeProfitFibRatioGreen = config.takeProfitFibRatioGreen,
            takeProfitFibRatioRed = config.takeProfitFibRatioRed,
            takeProfitToStopLossRatio = config.takeProfitToStopLossRatio,
            closedBarRefetchSettleMs = config.closedBarRefetchSettleMs,
            stopAfterOpenMinutes = config.stopAfterOpenMinutes,
            trailingStopTriggerFractionOfEntryToTp = config.trailingStopTriggerFractionOfEntryToTp,
            trailingStopArmFractionOfEntryToStop = config.trailingStopArmFractionOfEntryToStop,
            enableLiquidityRangeDailyAtr = config.enables.liquidityRangeDailyAtr,
            enableClosePositionGate = config.enables.closePositionGate,
            enableOpenDeadline = config.enables.openDeadline,
            enableAdjustableTrailingStop = config.enables.adjustableTrailingStop,
            enableFiveMinuteConfirmation = config.enables.fiveMinuteConfirmation,
            minGrossProfit = config.minGrossProfit,
            invertTradeSide = config.invertTradeSide,
            greenSkipClosePositionBelow = config.greenSkipClosePositionBelow,
            greenSkipClosePositionAbove = config.greenSkipClosePositionAbove,
            redSkipClosePositionBelow = config.redSkipClosePositionBelow,
            redSkipClosePositionAbove = config.redSkipClosePositionAbove,
            greenClosePositionBelowAction = config.greenClosePositionBelowAction.name,
            greenClosePositionAboveAction = config.greenClosePositionAboveAction.name,
            redClosePositionBelowAction = config.redClosePositionBelowAction.name,
            redClosePositionAboveAction = config.redClosePositionAboveAction.name,
            greenLiquidityBarAction = config.greenLiquidityBarAction.name,
            redLiquidityBarAction = config.redLiquidityBarAction.name
        )
}
