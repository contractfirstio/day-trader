package daytrader.data.persistence

import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables

object TouchTurnRuleConfigPersistence {
    fun toDomain(record: TouchTurnRuleConfigRecord?): TouchTurnRuleConfig =
        record?.let {
            TouchTurnRuleConfig(
                atrLiquidityRatio = it.atrLiquidityRatio,
                volumeExhaustionRatio = it.volumeExhaustionRatio,
                atrLookbackPeriods = it.atrLookbackPeriods,
                dailyAtrLookbackPeriods = it.dailyAtrLookbackPeriods,
                volumeSmaPeriods = it.volumeSmaPeriods,
                closePositionShortMax = it.closePositionShortMax,
                closePositionLongMin = it.closePositionLongMin,
                barLiveDivergenceMaxRatioOfRange = it.barLiveDivergenceMaxRatioOfRange,
                entryTouchBufferRatioOfRange = it.entryTouchBufferRatioOfRange,
                entryInwardOffsetRatioOfRange = it.entryInwardOffsetRatioOfRange,
                takeProfitFibRatioGreen = it.takeProfitFibRatioGreen,
                takeProfitFibRatioRed = it.takeProfitFibRatioRed,
                takeProfitToStopLossRatio = it.takeProfitToStopLossRatio,
                closeConfirmationAfterCloseMs = it.closeConfirmationAfterCloseMs,
                closedBarRefetchSettleMs = it.closedBarRefetchSettleMs,
                volumeBufferObservationMs = it.volumeBufferObservationMs,
                stopAfterOpenMinutes = it.stopAfterOpenMinutes,
                enables = TouchTurnRuleEnables(
                    liquidityRange15mAtr = it.enableLiquidityRange15mAtr ?: it.enableLiquidityRange,
                    liquidityRangeDailyAtr = it.enableLiquidityRangeDailyAtr,
                    notDoji = it.enableNotDoji,
                    volumeExhaustion = it.enableVolumeExhaustion,
                    barCloseTurn = it.enableBarCloseTurn,
                    entryWindow = it.enableEntryWindow,
                    liveQuoteRequired = it.enableLiveQuoteRequired,
                    liveBarAgreement = it.enableLiveBarAgreement,
                    liveTurnConfirmation = it.enableLiveTurnConfirmation,
                    liveEntryTouchable = it.enableLiveEntryTouchable,
                    postEntryVolumeBuffer = it.enablePostEntryVolumeBuffer,
                    openDeadline = it.enableOpenDeadline,
                    macroTrendAlignment = it.enableMacroTrendAlignment,
                    stockTrendAlignment = it.enableStockTrendAlignment
                )
            )
        } ?: TouchTurnRuleConfig.DEFAULT

    fun toRecord(config: TouchTurnRuleConfig): TouchTurnRuleConfigRecord =
        TouchTurnRuleConfigRecord(
            atrLiquidityRatio = config.atrLiquidityRatio,
            volumeExhaustionRatio = config.volumeExhaustionRatio,
            atrLookbackPeriods = config.atrLookbackPeriods,
            dailyAtrLookbackPeriods = config.dailyAtrLookbackPeriods,
            volumeSmaPeriods = config.volumeSmaPeriods,
            closePositionShortMax = config.closePositionShortMax,
            closePositionLongMin = config.closePositionLongMin,
            barLiveDivergenceMaxRatioOfRange = config.barLiveDivergenceMaxRatioOfRange,
            entryTouchBufferRatioOfRange = config.entryTouchBufferRatioOfRange,
            entryInwardOffsetRatioOfRange = config.entryInwardOffsetRatioOfRange,
            takeProfitFibRatioGreen = config.takeProfitFibRatioGreen,
            takeProfitFibRatioRed = config.takeProfitFibRatioRed,
            takeProfitToStopLossRatio = config.takeProfitToStopLossRatio,
            closeConfirmationAfterCloseMs = config.closeConfirmationAfterCloseMs,
            closedBarRefetchSettleMs = config.closedBarRefetchSettleMs,
            volumeBufferObservationMs = config.volumeBufferObservationMs,
            stopAfterOpenMinutes = config.stopAfterOpenMinutes,
            enableLiquidityRange = config.enables.liquidityRange15mAtr,
            enableLiquidityRange15mAtr = config.enables.liquidityRange15mAtr,
            enableLiquidityRangeDailyAtr = config.enables.liquidityRangeDailyAtr,
            enableNotDoji = config.enables.notDoji,
            enableVolumeExhaustion = config.enables.volumeExhaustion,
            enableBarCloseTurn = config.enables.barCloseTurn,
            enableEntryWindow = config.enables.entryWindow,
            enableLiveQuoteRequired = config.enables.liveQuoteRequired,
            enableLiveBarAgreement = config.enables.liveBarAgreement,
            enableLiveTurnConfirmation = config.enables.liveTurnConfirmation,
            enableLiveEntryTouchable = config.enables.liveEntryTouchable,
            enablePostEntryVolumeBuffer = config.enables.postEntryVolumeBuffer,
            enableOpenDeadline = config.enables.openDeadline,
            enableMacroTrendAlignment = config.enables.macroTrendAlignment,
            enableStockTrendAlignment = config.enables.stockTrendAlignment
        )
}
