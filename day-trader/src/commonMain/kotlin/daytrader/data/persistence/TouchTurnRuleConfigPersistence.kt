package daytrader.data.persistence

import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables

object TouchTurnRuleConfigPersistence {
    fun toDomain(record: TouchTurnRuleConfigRecord?): TouchTurnRuleConfig =
        record?.let {
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
                    skipGreenLiquidityBar = it.enableSkipGreenLiquidityBar,
                    skipRedLiquidityBar = it.enableSkipRedLiquidityBar,
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
                redSkipClosePositionAbove = it.redSkipClosePositionAbove
            )
        } ?: TouchTurnRuleConfig.DEFAULT

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
            enableSkipGreenLiquidityBar = config.enables.skipGreenLiquidityBar,
            enableSkipRedLiquidityBar = config.enables.skipRedLiquidityBar,
            enableClosePositionGate = config.enables.closePositionGate,
            enableOpenDeadline = config.enables.openDeadline,
            enableAdjustableTrailingStop = config.enables.adjustableTrailingStop,
            enableFiveMinuteConfirmation = config.enables.fiveMinuteConfirmation,
            minGrossProfit = config.minGrossProfit,
            invertTradeSide = config.invertTradeSide,
            greenSkipClosePositionBelow = config.greenSkipClosePositionBelow,
            greenSkipClosePositionAbove = config.greenSkipClosePositionAbove,
            redSkipClosePositionBelow = config.redSkipClosePositionBelow,
            redSkipClosePositionAbove = config.redSkipClosePositionAbove
        )
}
