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
                takeProfitFibRatioGreen = it.takeProfitFibRatioGreen,
                takeProfitFibRatioRed = it.takeProfitFibRatioRed,
                takeProfitToStopLossRatio = it.takeProfitToStopLossRatio,
                closedBarRefetchSettleMs = it.closedBarRefetchSettleMs,
                stopAfterOpenMinutes = it.stopAfterOpenMinutes,
                trailingStopTriggerFractionOfEntryToTp = it.trailingStopTriggerFractionOfEntryToTp,
                trailingStopTrailFractionOfEntryToStop = it.trailingStopTrailFractionOfEntryToStop,
                requiredExtremeBounceCount = it.requiredExtremeBounceCount,
                bounceTouchZoneRatioOfRange = it.bounceTouchZoneRatioOfRange,
                bounceRecoveryRatioOfRange = it.bounceRecoveryRatioOfRange,
                enables = TouchTurnRuleEnables(
                    liquidityRangeDailyAtr = it.enableLiquidityRangeDailyAtr,
                    openDeadline = it.enableOpenDeadline,
                    adjustableTrailingStop = it.enableAdjustableTrailingStop,
                    bounceRejection = it.enableBounceRejection
                )
            )
        } ?: TouchTurnRuleConfig.DEFAULT

    fun toRecord(config: TouchTurnRuleConfig): TouchTurnRuleConfigRecord =
        TouchTurnRuleConfigRecord(
            atrLiquidityRatio = config.atrLiquidityRatio,
            dailyAtrLookbackPeriods = config.dailyAtrLookbackPeriods,
            entryInwardOffsetRatioOfRange = config.entryInwardOffsetRatioOfRange,
            takeProfitFibRatioGreen = config.takeProfitFibRatioGreen,
            takeProfitFibRatioRed = config.takeProfitFibRatioRed,
            takeProfitToStopLossRatio = config.takeProfitToStopLossRatio,
            closedBarRefetchSettleMs = config.closedBarRefetchSettleMs,
            stopAfterOpenMinutes = config.stopAfterOpenMinutes,
            trailingStopTriggerFractionOfEntryToTp = config.trailingStopTriggerFractionOfEntryToTp,
            trailingStopTrailFractionOfEntryToStop = config.trailingStopTrailFractionOfEntryToStop,
            requiredExtremeBounceCount = config.requiredExtremeBounceCount,
            bounceTouchZoneRatioOfRange = config.bounceTouchZoneRatioOfRange,
            bounceRecoveryRatioOfRange = config.bounceRecoveryRatioOfRange,
            enableLiquidityRangeDailyAtr = config.enables.liquidityRangeDailyAtr,
            enableOpenDeadline = config.enables.openDeadline,
            enableAdjustableTrailingStop = config.enables.adjustableTrailingStop,
            enableBounceRejection = config.enables.bounceRejection
        )
}
